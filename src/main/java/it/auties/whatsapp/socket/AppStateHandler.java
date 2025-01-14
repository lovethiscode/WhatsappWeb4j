package it.auties.whatsapp.socket;

import it.auties.bytes.Bytes;
import it.auties.whatsapp.binary.PatchType;
import it.auties.whatsapp.crypto.AesCbc;
import it.auties.whatsapp.crypto.Hmac;
import it.auties.whatsapp.crypto.LTHash;
import it.auties.whatsapp.exception.HmacValidationException;
import it.auties.whatsapp.model.action.*;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatMute;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.media.DownloadResult;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.setting.LocaleSetting;
import it.auties.whatsapp.model.setting.PushNameSetting;
import it.auties.whatsapp.model.setting.UnarchiveChatsSetting;
import it.auties.whatsapp.model.sync.*;
import it.auties.whatsapp.util.*;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static it.auties.whatsapp.api.ErrorHandler.Location.*;
import static it.auties.whatsapp.model.request.Node.ofChildren;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Map.of;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.CompletableFuture.completedFuture;

class AppStateHandler
        implements JacksonProvider {
    public static final int PULL_TIMEOUT = 30;
    public static final int PUSH_TIMEOUT = 120;
    private static final int PULL_ATTEMPTS = 3;
    private final SocketHandler socketHandler;
    private final Semaphore semaphore;
    private CountDownLatch countDownLatch;

    @SneakyThrows
    protected AppStateHandler(SocketHandler socketHandler) {
        this.socketHandler = socketHandler;
        this.semaphore = new Semaphore(1);
        this.countDownLatch = new CountDownLatch(1);
    }

    protected CompletableFuture<Void> push(@NonNull PatchRequest patch) {
        return CompletableFuture.runAsync(() -> acquireLock(true))
                .thenComposeAsync(result -> sendPullRequest(patch.type()))
                .thenApplyAsync(result -> createPushRequest(patch))
                .thenComposeAsync(this::sendPush)
                .thenRunAsync(semaphore::release)
                .exceptionallyAsync(throwable -> {
                    semaphore.release();
                    return socketHandler.errorHandler()
                            .handleFailure(PUSH_APP_STATE, throwable);
                })
                .orTimeout(PUSH_TIMEOUT, TimeUnit.SECONDS);
    }

    private PushRequest createPushRequest(PatchRequest patch) {
        try {
            var oldState = socketHandler.keys()
                    .findHashStateByName(patch.type())
                    .orElseGet(() -> new LTHashState(patch.type()));
            var newState = oldState.copy();

            var key = socketHandler.keys()
                    .appKey();

            var index = patch.index()
                    .getBytes(StandardCharsets.UTF_8);
            var actionData = ActionDataSync.builder()
                    .index(index)
                    .value(patch.sync())
                    .padding(new byte[0])
                    .version(patch.version())
                    .build();
            var encoded = PROTOBUF.writeValueAsBytes(actionData);

            var mutationKeys = MutationKeys.of(key.keyData()
                                                       .keyData());
            var encrypted = AesCbc.encryptAndPrefix(encoded, mutationKeys.encKey());
            var valueMac = generateMac(patch.operation(), encrypted, key.keyId()
                    .keyId(), mutationKeys.macKey());
            var indexMac = Hmac.calculateSha256(index, mutationKeys.indexKey());

            var generator = new LTHash(newState);
            generator.mix(indexMac, valueMac, patch.operation());
            var result = generator.finish();
            newState.hash(result.hash());
            newState.indexValueMap(result.indexValueMap());
            newState.version(newState.version() + 1);

            var syncId = new KeyId(key.keyId()
                                           .keyId());
            var record = RecordSync.builder()
                    .index(new IndexSync(indexMac))
                    .value(new ValueSync(Bytes.of(encrypted, valueMac)
                                                 .toByteArray()))
                    .keyId(syncId)
                    .build();
            var mutation = MutationSync.builder()
                    .operation(patch.operation())
                    .record(record)
                    .build();

            var snapshotMac = generateSnapshotMac(newState.hash(), newState.version(), patch.type(),
                                                  mutationKeys.snapshotMacKey());
            var patchMac = generatePatchMac(snapshotMac, valueMac, newState.version(), patch.type(),
                                            mutationKeys.patchMacKey());
            var sync = PatchSync.builder()
                    .patchMac(patchMac)
                    .snapshotMac(snapshotMac)
                    .keyId(syncId)
                    .mutations(List.of(mutation))
                    .build();
            newState.indexValueMap()
                    .put(Bytes.of(indexMac)
                                 .toBase64(), valueMac);

            return new PushRequest(patch, oldState, newState, sync);
        } catch (Throwable throwable) {
            throw new RuntimeException("Cannot create patch %s".formatted(patch), throwable);
        }
    }

    private CompletableFuture<Void> sendPush(PushRequest request) {
        try {
            var body = ofChildren("collection", of("name", request.patch()
                                          .type(), "version", request.newState()
                                                           .version() - 1, "return_snapshot", false),
                                  Node.of("patch", PROTOBUF.writeValueAsBytes(request.sync())));
            return socketHandler.sendQuery("set", "w:sync:app:state", Node.ofChildren("sync", body))
                    .thenAcceptAsync(this::parseSyncRequest)
                    .thenRunAsync(() -> socketHandler.keys()
                            .putState(request.patch()
                                              .type(), request.newState()))
                    .thenRunAsync(() -> handleSyncRequest(request.patch()
                                                                  .type(), request.sync(), request.oldState(),
                                                          request.newState()
                                                                  .version()));
        } catch (Throwable throwable) {
            throw new RuntimeException("Cannot push patch", throwable);
        }
    }

    private void handleSyncRequest(PatchType patchType, PatchSync patch, LTHashState oldState, long newVersion) {
        decodePatches(patchType, 0, List.of(patch.withVersion(new VersionSync(newVersion))), oldState).records()
                .forEach(this::processActions);
    }

    @SuppressWarnings("UnusedReturnValue")
    protected CompletableFuture<Void> pull(boolean initial, PatchType... patchTypes) {
        return CompletableFuture.runAsync(() -> acquireLock(!initial))
                .thenComposeAsync(ignored -> sendPullRequest(patchTypes))
                .thenRunAsync(() -> {
                    countDownLatch.countDown();
                    socketHandler.store()
                            .initialAppSync(true);
                    semaphore.release();
                })
                .exceptionallyAsync(exception -> {
                    if (initial) {
                        countDownLatch.countDown();
                        return socketHandler.errorHandler()
                                .handleFailure(INITIAL_APP_STATE_SYNC, exception);
                    }

                    return socketHandler.errorHandler()
                            .handleFailure(PULL_APP_STATE, exception);
                })
                .orTimeout(PULL_TIMEOUT, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> sendPullRequest(PatchType... patchTypes) {
        Validate.isTrue(patchTypes.length != 0, "Cannot pull no patches", IllegalArgumentException.class);
        var versions = new HashMap<PatchType, Long>();
        var attempts = new HashMap<PatchType, Integer>();
        return pull(Arrays.asList(patchTypes), versions, attempts);
    }

    private CompletableFuture<Void> pull(List<PatchType> patchTypes, Map<PatchType, Long> versions,
            Map<PatchType, Integer> attempts) {
        var tempStates = new HashMap<PatchType, LTHashState>();
        var nodes = patchTypes.stream()
                .map(patchType -> createStateWithVersion(patchType, versions))
                .peek(state -> tempStates.put(state.name(), state))
                .map(LTHashState::toNode)
                .toList();
        return socketHandler.sendQuery("set", "w:sync:app:state", Node.ofChildren("sync", nodes))
                .thenApplyAsync(this::parseSyncRequest)
                .thenApplyAsync(records -> decodeSyncs(versions, attempts, tempStates, records))
                .thenComposeAsync(remaining -> remaining.isEmpty() ?
                        completedFuture(null) :
                        pull(remaining, versions, attempts));
    }

    private List<PatchType> decodeSyncs(Map<PatchType, Long> versions, Map<PatchType, Integer> attempts,
            Map<PatchType, LTHashState> tempStates, List<SnapshotSyncRecord> records) {
        return records.stream()
                .map(record -> decodeSync(record, versions, attempts, tempStates))
                .peek(chunk -> chunk.records()
                        .forEach(this::processActions))
                .filter(PatchChunk::hasMore)
                .map(PatchChunk::patchType)
                .toList();
    }

    private PatchChunk decodeSync(SnapshotSyncRecord record, Map<PatchType, Long> versions,
            Map<PatchType, Integer> attempts, Map<PatchType, LTHashState> tempStates) {
        try {
            var results = new ArrayList<ActionDataSync>();
            if (record.hasSnapshot()) {
                var decodedSnapshot = decodeSnapshot(record.patchType(), versions.get(record.patchType()),
                                                     record.snapshot());
                results.addAll(decodedSnapshot.records());
                tempStates.put(record.patchType(), decodedSnapshot.state());
                socketHandler.keys()
                        .putState(record.patchType(), decodedSnapshot.state());
            }

            if (record.hasPatches()) {
                var decodedPatches = decodePatches(record.patchType(), versions.get(record.patchType()),
                                                   record.patches(), tempStates.get(record.patchType()));
                results.addAll(decodedPatches.records());
                socketHandler.keys()
                        .putState(record.patchType(), decodedPatches.state());
            }

            return new PatchChunk(record.patchType(), results, record.hasMore());
        } catch (Throwable throwable) {
            var hashState = new LTHashState(record.patchType());
            socketHandler.keys()
                    .putState(record.patchType(), hashState);
            attempts.put(record.patchType(), attempts.getOrDefault(record.patchType(), 0) + 1);

            if (attempts.get(record.patchType()) >= PULL_ATTEMPTS) {
                throw new RuntimeException("Cannot parse patch(%s tries)".formatted(PULL_ATTEMPTS), throwable);
            }

            return decodeSync(record, versions, attempts, tempStates);
        }
    }

    private LTHashState createStateWithVersion(PatchType name, Map<PatchType, Long> versions) {
        var state = socketHandler.keys()
                .findHashStateByName(name)
                .orElse(null);
        if (state == null) {
            versions.put(name, 0L);
            return new LTHashState(name);
        }

        if (!versions.containsKey(name)) {
            versions.put(name, state.version());
        }

        return state;
    }

    private List<SnapshotSyncRecord> parseSyncRequest(Node node) {
        return Optional.ofNullable(node)
                .flatMap(sync -> sync.findNode("sync"))
                .map(sync -> sync.findNodes("collection"))
                .stream()
                .flatMap(Collection::stream)
                .map(this::parseSync)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<SnapshotSyncRecord> parseSync(Node sync) {
        var name = PatchType.of(sync.attributes()
                                        .getString("name"));
        var type = sync.attributes()
                .getString("type");
        if (Objects.equals(type, "error")) {
            return Optional.empty();
        }

        var more = sync.attributes()
                .getBoolean("has_more_patches");
        var snapshotSync = sync.findNode("snapshot")
                .map(this::decodeSnapshot)
                .orElse(null);
        var versionCode = sync.attributes()
                .getInt("version");
        var patches = sync.findNode("patches")
                .orElse(sync)
                .findNodes("patch")
                .stream()
                .map(patch -> decodePatch(patch, versionCode))
                .flatMap(Optional::stream)
                .toList();
        return Optional.of(new SnapshotSyncRecord(name, snapshotSync, patches, more));
    }

    @SneakyThrows
    private SnapshotSync decodeSnapshot(Node snapshot) {
        if (snapshot == null) {
            return null;
        }

        var blob = PROTOBUF.readMessage(snapshot.contentAsBytes()
                                                .orElseThrow(), ExternalBlobReference.class);
        var syncedData = Medias.download(blob);
        Validate.isTrue(syncedData.status() == DownloadResult.Status.SUCCESS, "Cannot download snapshot");
        return PROTOBUF.readMessage(syncedData.media()
                                            .get(), SnapshotSync.class);
    }

    @SneakyThrows
    private Optional<PatchSync> decodePatch(Node patch, long versionCode) {
        if (!patch.hasContent()) {
            return Optional.empty();
        }

        var patchSync = PROTOBUF.readMessage(patch.contentAsBytes()
                                                     .orElseThrow(), PatchSync.class);
        if (!patchSync.hasVersion()) {
            var version = new VersionSync(versionCode + 1);
            patchSync.version(version);
        }

        return Optional.of(patchSync);
    }

    private void processActions(ActionDataSync mutation) {
        var value = mutation.value();
        if (value == null) {
            return;
        }

        var action = value.action();
        if (action != null) {
            var messageIndex = mutation.messageIndex();
            var targetContact = messageIndex.chatJid().flatMap(socketHandler.store()::findContactByJid);
            var targetChat = messageIndex.chatJid().flatMap(socketHandler.store()::findChatByJid);
            var targetMessage = targetChat.flatMap(chat -> socketHandler.store().findMessageById(chat, mutation.messageIndex().messageId().orElse(null)));
            switch (action) {
                case ClearChatAction clearChatAction -> clearMessages(targetChat.orElse(null), clearChatAction);
                case ContactAction contactAction -> updateName(targetContact.orElseGet(() -> {
                    var contact = socketHandler.store()
                            .addContact(messageIndex.chatJid().orElseThrow());
                    socketHandler.onNewContact(contact);
                    return contact;
                }), targetChat.orElseGet(() -> socketHandler.store()
                        .addChat(messageIndex.chatJid().orElseThrow())), contactAction);
                case DeleteChatAction ignored -> targetChat.ifPresent(Chat::removeMessages);
                case DeleteMessageForMeAction ignored ->
                        targetMessage.ifPresent(message -> targetChat.ifPresent(chat -> deleteMessage(message, chat)));
                case MarkChatAsReadAction markAction -> targetChat.ifPresent(chat -> chat.unreadMessagesCount(
                        markAction.read() ?
                                0 :
                                -1));
                case MuteAction muteAction ->
                        targetChat.ifPresent(chat -> chat.mute(ChatMute.muted(muteAction.muteEndTimestamp())));
                case PinAction pinAction -> targetChat.ifPresent(chat -> chat.pinned(pinAction.pinned() ?
                                                                                             mutation.value()
                                                                                                     .timestamp() :
                                                                                             0));
                case StarAction starAction -> targetMessage.ifPresent(message -> message.starred(starAction.starred()));
                case ArchiveChatAction archiveChatAction ->
                        targetChat.ifPresent(chat -> chat.archived(archiveChatAction.archived()));
                case TimeFormatAction timeFormatAction -> socketHandler.store()
                        .twentyFourHourFormat(timeFormatAction.twentyFourHourFormatEnabled());
                default -> {

                }
            }

            socketHandler.onAction(action, messageIndex);
        }

        var setting = value.setting();
        if (setting != null) {
            switch (setting) {
                case EphemeralSetting ephemeralSetting -> showEphemeralMessageWarning(ephemeralSetting);
                case LocaleSetting localeSetting -> socketHandler.updateLocale(localeSetting.locale(),
                                                                               socketHandler.store()
                                                                                       .userLocale());
                case PushNameSetting pushNameSetting -> socketHandler.updateUserName(pushNameSetting.name(),
                                                                                     socketHandler.store()
                                                                                             .userName());
                case UnarchiveChatsSetting unarchiveChatsSetting -> socketHandler.store()
                        .unarchiveChats(unarchiveChatsSetting.unarchiveChats());
                default -> {
                }
            }

            socketHandler.onSetting(setting);
        }

        var features = mutation.value()
                .primaryFeature();
        if (features != null && !features.flags()
                .isEmpty()) {
            socketHandler.onFeatures(features);
        }
    }

    private void showEphemeralMessageWarning(EphemeralSetting ephemeralSetting) {
        var logger = System.getLogger("AppStateHandler");
        logger.log(WARNING, "An ephemeral status update was received as a setting. " + "Data: %s".formatted(
                ephemeralSetting) + "This should not be possible." + " Open an issue on Github please");
    }

    private void clearMessages(Chat targetChat, ClearChatAction clearChatAction) {
        if (targetChat == null || clearChatAction.messageRange() == null || clearChatAction.messageRange()
                .messages() == null) {
            return;
        }

        clearChatAction.messageRange()
                .messages()
                .stream()
                .map(SyncActionMessage::key)
                .filter(Objects::nonNull)
                .forEach(key -> targetChat.removeMessage(entry -> Objects.equals(entry.id(), key.id())));
    }

    private void updateName(Contact contact, Chat chat, ContactAction contactAction) {
        contact.fullName(contactAction.fullName());
        contact.shortName(contactAction.firstName());
        var name = requireNonNullElse(contactAction.firstName(), contactAction.fullName());
        chat.name(name);
    }

    private void deleteMessage(MessageInfo message, Chat chat) {
        chat.removeMessage(message);
        socketHandler.onMessageDeleted(message, false);
    }

    private SyncRecord decodePatches(PatchType name, long minimumVersion, List<PatchSync> patches, LTHashState state) {
        var newState = state.copy();
        var results = patches.stream()
                .map(patch -> decodePatch(name, minimumVersion, newState, patch))
                .flatMap(Optional::stream)
                .map(MutationsRecord::records)
                .flatMap(Collection::stream)
                .toList();
        return new SyncRecord(newState, results);
    }

    @SneakyThrows
    private Optional<MutationsRecord> decodePatch(PatchType patchType, long minimumVersion, LTHashState newState,
            PatchSync patch) {
        if (patch.hasExternalMutations()) {
            var blob = Medias.download(patch.externalMutations());
            Validate.isTrue(blob.status() == DownloadResult.Status.SUCCESS, "Cannot download mutations");
            var mutationsSync = PROTOBUF.readMessage(blob.media()
                                                             .get(), MutationsSync.class);
            patch.mutations()
                    .addAll(mutationsSync.mutations());
        }

        newState.version(patch.version());
        Validate.isTrue(Arrays.equals(calculateSyncMac(patch, patchType), patch.patchMac()), "sync_mac",
                        HmacValidationException.class);
        var mutations = decodeMutations(patch.mutations(), newState);
        newState.hash(mutations.result()
                              .hash());
        newState.indexValueMap(mutations.result()
                                       .indexValueMap());
        Validate.isTrue(Arrays.equals(generatePatchMac(patchType, newState, patch), patch.snapshotMac()), "patch_mac",
                        HmacValidationException.class);

        return Optional.of(mutations)
                .filter(ignored -> minimumVersion == 0 || newState.version() > minimumVersion);
    }

    private byte[] generatePatchMac(PatchType name, LTHashState newState, PatchSync patch) {
        var mutationKeys = getMutationKeys(patch.keyId());
        return generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey());
    }

    private byte[] calculateSyncMac(PatchSync patch, PatchType patchType) {
        var mutationKeys = getMutationKeys(patch.keyId());
        var mutationMacs = patch.mutations()
                .stream()
                .map(mutation -> mutation.record()
                        .value()
                        .blob())
                .map(Bytes::of)
                .map(binary -> binary.slice(-SignalSpecification.KEY_LENGTH))
                .reduce(Bytes.newBuffer(), Bytes::append)
                .toByteArray();
        return generatePatchMac(patch.snapshotMac(), mutationMacs, patch.version(), patchType,
                                mutationKeys.patchMacKey());
    }

    private SyncRecord decodeSnapshot(PatchType name, long minimumVersion, SnapshotSync snapshot) {
        var newState = new LTHashState(name, snapshot.version()
                .version());
        var mutations = decodeMutations(snapshot.records(), newState);
        newState.hash(mutations.result()
                              .hash());
        newState.indexValueMap(mutations.result()
                                       .indexValueMap());
        var mutationKeys = getMutationKeys(snapshot.keyId());
        Validate.isTrue(Arrays.equals(snapshot.mac(), generateSnapshotMac(newState.hash(), newState.version(), name,
                                                                          mutationKeys.snapshotMacKey())),
                        "decode_snapshot", HmacValidationException.class);

        if (minimumVersion == 0 || newState.version() > minimumVersion) {
            mutations.records()
                    .clear();
        }

        return new SyncRecord(newState, mutations.records());
    }

    private MutationKeys getMutationKeys(KeyId snapshot) {
        var encryptedKey = socketHandler.keys()
                .findAppKeyById(snapshot.id())
                .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
        return MutationKeys.of(encryptedKey.keyData()
                                       .keyData());
    }

    private MutationsRecord decodeMutations(List<? extends Syncable> syncs, LTHashState state) {
        var generator = new LTHash(state);
        var mutations = syncs.stream()
                .map(mutation -> decodeMutation(mutation.operation(), mutation.record(), generator))
                .collect(Collectors.toList());
        return new MutationsRecord(generator.finish(), mutations);
    }

    @SneakyThrows
    private ActionDataSync decodeMutation(RecordSync.Operation operation, RecordSync sync, LTHash generator) {
        var mutationKeys = getMutationKeys(sync.keyId());

        var blob = Bytes.of(sync.value()
                                    .blob());
        var encryptedBlob = blob.cut(-SignalSpecification.KEY_LENGTH)
                .toByteArray();
        var encryptedMac = blob.slice(-SignalSpecification.KEY_LENGTH)
                .toByteArray();
        Validate.isTrue(Arrays.equals(encryptedMac, generateMac(operation, encryptedBlob, sync.keyId()
                .id(), mutationKeys.macKey())), "decode_mutation", HmacValidationException.class);

        var result = AesCbc.decrypt(encryptedBlob, mutationKeys.encKey());
        var actionSync = PROTOBUF.readMessage(result, ActionDataSync.class);
        Validate.isTrue(Arrays.equals(sync.index()
                                              .blob(),
                                      Hmac.calculateSha256(actionSync.index(), mutationKeys.indexKey())),
                        "decode_mutation", HmacValidationException.class);
        generator.mix(sync.index()
                              .blob(), encryptedMac, operation);
        return actionSync;
    }

    private byte[] generateMac(RecordSync.Operation operation, byte[] data, byte[] keyId, byte[] key) {
        var keyData = Bytes.of(operation.content())
                .append(keyId)
                .toByteArray();

        var last = Bytes.newBuffer(SignalSpecification.MAC_LENGTH - 1)
                .append(keyData.length)
                .toByteArray();

        var total = Bytes.of(keyData, data, last)
                .toByteArray();
        return Bytes.of(Hmac.calculateSha512(total, key))
                .cut(SignalSpecification.KEY_LENGTH)
                .toByteArray();
    }

    private byte[] generateSnapshotMac(byte[] ltHash, long version, PatchType patchType, byte[] key) {
        var total = Bytes.of(ltHash)
                .append(BytesHelper.longToBytes(version))
                .append(patchType.toString()
                                .getBytes(StandardCharsets.UTF_8))
                .toByteArray();
        return Hmac.calculateSha256(total, key);
    }

    private byte[] generatePatchMac(byte[] snapshotMac, byte[] valueMac, long version, PatchType patchType,
            byte[] key) {
        var total = Bytes.of(snapshotMac)
                .append(valueMac)
                .append(BytesHelper.longToBytes(version))
                .append(patchType.toString()
                                .getBytes(StandardCharsets.UTF_8))
                .toByteArray();
        return Hmac.calculateSha256(total, key);
    }

    private void acquireLock(boolean necessary) {
        try {
            if (!necessary) {
                return;
            }

            semaphore.acquire();
        } catch (InterruptedException exception) {
            throw new RuntimeException("Cannot lock", exception);
        }
    }

    public void awaitReady() {
        try {
            countDownLatch.await();
        } catch (InterruptedException exception) {
            throw new RuntimeException("Cannot await app state", exception);
        }
    }

    public void dispose(){
        semaphore.release();
        this.countDownLatch = new CountDownLatch(1);
    }

    private record SyncRecord(LTHashState state, List<ActionDataSync> records) {

    }

    private record SnapshotSyncRecord(PatchType patchType, SnapshotSync snapshot, List<PatchSync> patches,
                                      boolean hasMore) {
        public boolean hasSnapshot() {
            return snapshot != null;
        }

        public boolean hasPatches() {
            return patches != null && !patches.isEmpty();
        }
    }

    private record MutationsRecord(LTHash.Result result, List<ActionDataSync> records) {

    }

    private record PatchChunk(PatchType patchType, List<ActionDataSync> records, boolean hasMore) {

    }

    private record PushRequest(PatchRequest patch, LTHashState oldState, LTHashState newState, PatchSync sync) {

    }
}
