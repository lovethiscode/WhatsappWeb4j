package it.auties.whatsapp.ci;

import it.auties.bytes.Bytes;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.Keys;
import it.auties.whatsapp.controller.Store;
import it.auties.whatsapp.github.GithubActions;
import it.auties.whatsapp.listener.Listener;
import it.auties.whatsapp.model.button.Button;
import it.auties.whatsapp.model.button.ButtonText;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatEphemeralTimer;
import it.auties.whatsapp.model.chat.ChatMute;
import it.auties.whatsapp.model.chat.GroupPolicy;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.message.button.ButtonsMessage;
import it.auties.whatsapp.model.message.standard.*;
import it.auties.whatsapp.util.JacksonProvider;
import it.auties.whatsapp.utils.ConfigUtils;
import it.auties.whatsapp.utils.MediaUtils;
import lombok.SneakyThrows;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
public class WhatsappAPITest implements Listener, JacksonProvider {
    private static Whatsapp api;
    private static CountDownLatch latch;
    private static ContactJid contact;
    private static ContactJid group;
    private static MessageInfo info;

    @BeforeAll
    public void init() throws IOException, InterruptedException {
        createApi();
        loadConfig();
        createLatch();
        api.connect();
        latch.await();
    }

    private void createApi() throws IOException {
        log("Initializing api to start testing...");
        if (!GithubActions.isActionsEnvironment()) {
            log("Detected local environment");
            api = Whatsapp.lastConnection();
            api.addListener(this);
            return;
        }

        log("Detected github actions environment");
        api = Whatsapp.newConnection(Whatsapp.Options.defaultOptions(),
                loadGithubParameter(GithubActions.STORE_NAME, Store.class),
                loadGithubParameter(GithubActions.CREDENTIALS_NAME, Keys.class));
        api.addListener(this);
    }

    private <T> T loadGithubParameter(String parameter, Class<T> type) throws IOException {
        var keysJson = Base64.getDecoder()
                .decode(System.getenv(parameter));
        return JSON.readValue(keysJson, type);
    }

    private void loadConfig() throws IOException {
        if (GithubActions.isActionsEnvironment()) {
            log("Loading environment variables...");
            var jid = new String(Base64.getDecoder()
                    .decode(System.getenv(GithubActions.CONTACT_NAME)), StandardCharsets.UTF_8);
            contact = ContactJid.of(jid);
            log("Loaded environment variables...");
            return;
        }

        log("Loading configuration file...");
        var props = ConfigUtils.loadConfiguration();
        contact = ContactJid.of(
                Objects.requireNonNull(props.getProperty("contact"), "Missing contact property in config"));
        log("Loaded configuration file");
    }

    private void createLatch() {
        latch = new CountDownLatch(3);
    }

    @Override
    public void onLoggedIn() {
        latch.countDown();
        log("Logged in: -%s", latch.getCount());
    }

    @Override
    public void onChats() {
        latch.countDown();
        log("Got chats: -%s", latch.getCount());
    }

    @Override
    public void onContacts() {
        latch.countDown();
        log("Got contacts: -%s", latch.getCount());
    }

    @Test
    @Order(2)
    public void testChangeGlobalPresence() {
        log("Changing global presence...");
        api.changePresence(true)
                .join();
        log("Changed global presence...");
    }

    @Test
    @Order(3)
    public void testUserPresenceSubscription() {
        log("Subscribing to user presence...");
        var userPresenceResponse = api.subscribeToPresence(contact)
                .join();
        log("Subscribed to user presence: %s", userPresenceResponse);
    }

    @Test
    @Order(4)
    public void testPictureQuery() {
        log("Loading picture...");
        var picResponse = api.queryPic(contact)
                .join();
        log("Loaded picture at: %s", picResponse);
    }

    @Test
    @Order(5)
    public void testStatusQuery() {
        log("Querying %s's status...", contact);
        api.queryStatus(contact)
                .join()
                .ifPresentOrElse(status -> log("Queried %s", status), () -> log("%s doesn't have a status", contact));
    }

    @Test
    @Order(8)
    public void testMarkChat() {
        markAsUnread();
        markAsRead();
    }

    private void markAsUnread() {
        log("Marking chat as unread...");
        var markStatus = api.markUnread(contact)
                .join();
        log("Marked chat as unread: %s", markStatus);
    }

    private void markAsRead() {
        log("Marking chat as read...");
        var markStatus = api.markRead(contact)
                .join();
        log("Marked chat as read: %s", markStatus);
    }

    @Test
    @Order(9)
    public void testGroupCreation() {
        log("Creating group...");
        var response = api.create(Bytes.ofRandom(5)
                        .toHex(), contact)
                .join();
        group = response.jid();
        log("Created group: %s", response);
    }

    @Test
    @Order(10)
    public void testChangeIndividualPresence() {
        if (group == null) {
            testGroupCreation();
        }

        for (var presence : ContactStatus.values()) {
            log("Changing individual presence to %s...", presence.name());
            var response = api.changePresence(group, presence)
                    .join();
            log("Changed individual presence: %s", response);
        }
    }

    @Test
    @Order(11)
    public void testChangeGroupName() {
        if (group == null) {
            testGroupCreation();
        }

        log("Changing group name...");
        var changeGroupResponse = api.changeSubject(group, "omega")
                .join();
        log("Changed group name: %s", changeGroupResponse);
    }

    @RepeatedTest(2)
    @Order(12)
    public void testChangeGroupDescription() {
        if (group == null) {
            testGroupCreation();
        }

        log("Changing group description...");
        var changeGroupResponse = api.changeDescription(group, Bytes.ofRandom(12)
                        .toHex())
                .join();
        log("Changed group description: %s", changeGroupResponse);
    }

    @Test
    @Order(13)
    public void testRemoveGroupParticipant() {
        if (group == null) {
            testGroupCreation();
        }

        log("Removing %s...", contact);
        var changeGroupResponse = api.remove(group, contact)
                .join();
        log("Removed: %s", changeGroupResponse);
    }

    @Test
    @Order(14)
    public void testAddGroupParticipant() {
        if (group == null) {
            testGroupCreation();
        }

        log("Adding %s...", contact);
        var changeGroupResponse = api.add(group, contact)
                .join();
        log("Added: %s", changeGroupResponse);
    }

    @Test
    @Order(15)
    public void testPromotion() {
        if (group == null) {
            testGroupCreation();
        }

        log("Promoting %s...", contact);
        var changeGroupResponse = api.promote(group, contact)
                .join();
        log("Promoted: %s", changeGroupResponse);
    }

    @Test
    @Order(16)
    public void testDemotion() {
        if (group == null) {
            testGroupCreation();
        }

        log("Demoting %s...", contact);
        var changeGroupResponse = api.demote(group, contact)
                .join();
        log("Demoted: %s", changeGroupResponse);
    }

    @Test
    @Order(17)
    public void testChangeAllGroupSettings() {
        if (group == null) {
            testGroupCreation();
        }

        for (var policy : GroupPolicy.values()) {
            log("Changing settings to %s...", policy.name());
            api.changeWhoCanEditInfo(group, policy)
                    .join();
            api.changeWhoCanEditInfo(group, policy)
                    .join();
            log("Changed settings to %s", policy.name());
        }
    }

    @Test
    @Order(19)
    public void testGroupQuery() {
        if (group == null) {
            testGroupCreation();
        }

        log("Querying group %s...", group);
        api.queryGroupMetadata(group)
                .join();
        log("Queried group");
    }

    @Test
    @Order(20)
    public void testMute() {
        if (group == null) {
            testGroupCreation();
        }

        log("Muting chat...");
        var muteResponse = api.mute(group, ChatMute.mutedForOneWeek())
                .join();
        log("Muted chat: %s", muteResponse);
    }

    @Test
    @Order(21)
    public void testUnmute() {
        if (group == null) {
            testGroupCreation();
        }

        log("Unmuting chat...");
        var unmuteResponse = api.unmute(group)
                .join();
        log("Unmuted chat: %s", unmuteResponse);
    }

    @Test
    @Order(22)
    public void testArchive() {
        if (group == null) {
            testGroupCreation();
        }

        log("Archiving chat...");
        var archiveResponse = api.archive(group)
                .join();
        log("Archived chat: %s", archiveResponse);
    }

    @Test
    @Order(23)
    public void testUnarchive() {
        if (group == null) {
            testGroupCreation();
        }

        log("Unarchiving chat...");
        var unarchiveResponse = api.unarchive(group)
                .join();
        log("Unarchived chat: %s", unarchiveResponse);
    }

    @Test
    @Order(24)
    public void testPin() {
        if (group == null) {
            testGroupCreation();
        }

        if (api.store()
                .pinnedChats()
                .size() >= 3) {
            log("Skipping chat pinning as there are already three chats pinned...");
            return;
        }

        log("Pinning chat...");
        var pinResponse = api.pin(group)
                .join();
        log("Pinned chat: %s", pinResponse);
    }

    @Test
    @Order(25)
    public void testUnpin() {
        if (group == null) {
            testGroupCreation();
        }

        if (api.store()
                .pinnedChats()
                .size() >= 3) {
            log("Skipping chat unpinning as there are already three chats pinned...");
            return;
        }

        log("Unpinning chat...");
        var unpinResponse = api.unpin(group)
                .join();
        log("Unpinned chat: %s", unpinResponse);
    }

    @Test
    @Order(26)
    public void testTextBuilder() {
        log("Sending text...");
        info = api.sendMessage(contact, "Hello: https://www.youtube.com/watch?v=4boXExbbGCk")
                .join();
        log("Sent text: %s", info);
    }

    @Test
    @Order(27)
    public void deleteBuilder() {
        if(info == null){
            testTextBuilder();
        }

        log("Deleting for you...");
        api.delete(info, false)
                .join();
        log("Deleted for you");

        log("Deleting for everyone...");
        api.delete(info, true)
                .join();
        log("Deleted for everyone");
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    @Order(28)
    public void testButtonsBuilder() {
        log("Sending buttons...");
        var emptyButtons = ButtonsMessage.newButtonsWithoutHeaderBuilder()
                .body("A nice body")
                .footer("A nice footer")
                .buttons(createButtons())
                .build();
        api.sendMessage(contact, emptyButtons)
                .join();

        var textButtons = ButtonsMessage.newButtonsWithTextHeaderBuilder()
                .header("A nice header")
                .body("A nice body")
                .footer("A nice footer")
                .buttons(createButtons())
                .build();
        api.sendMessage(contact, textButtons)
                .join();

        var document = DocumentMessage.newDocumentBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes("http://www.orimi.com/pdf-test.pdf"))
                .title("Pdf test")
                .fileName("pdf-test.pdf")
                .pageCount(1)
                .build();
        var documentButtons = ButtonsMessage.newButtonsWithDocumentHeaderBuilder()
                .header(document)
                .body("A nice body")
                .footer("A nice footer")
                .buttons(createButtons())
                .build();
        api.sendMessage(contact, documentButtons)
                .join();

        var image = ImageMessage.newImageBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes(
                        "https://2.bp.blogspot.com/-DqXILvtoZFA/Wmmy7gRahnI/AAAAAAAAB0g/59c8l63QlJcqA0591t8-kWF739DiOQLcACEwYBhgL/s1600/pol-venere-botticelli-01.jpg"))
                .caption("Image test")
                .build();
        var imageButtons = ButtonsMessage.newButtonsWithImageHeaderBuilder()
                .header(image)
                .body("A nice body")
                .footer("A nice footer")
                .buttons(createButtons())
                .build();
        api.sendMessage(contact, imageButtons)
                .join();

        log("Sent buttons");
    }

    private List<Button> createButtons() {
        return IntStream.range(0, 3)
                .mapToObj("Button %s"::formatted)
                .map(Button::newTextResponseButton)
                .toList();
    }

    @Test
    @Order(29)
    public void testImageBuilder() {
        log("Sending image...");
        var image = ImageMessage.newImageBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes(
                        "https://2.bp.blogspot.com/-DqXILvtoZFA/Wmmy7gRahnI/AAAAAAAAB0g/59c8l63QlJcqA0591t8-kWF739DiOQLcACEwYBhgL/s1600/pol-venere-botticelli-01.jpg"))
                .caption("Image test")
                .build();
        var textResponse = api.sendMessage(contact, image)
                .join();
        log("Sent image: %s", textResponse);
    }

    @Test
    @Order(30)
    public void testAudioBuilder() {
        log("Sending audio...");
        var audio = AudioMessage.newAudioBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes("https://www.kozco.com/tech/organfinale.mp3"))
                .build();
        var textResponse = api.sendMessage(contact, audio)
                .join();
        log("Sent audio: %s", textResponse);
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    @Order(31)
    public void testVideoBuilder() {
        log("Sending video...");
        var video = VideoMessage.newVideoBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes("http://techslides.com/demos/sample-videos/small.mp4"))
                .caption("Video")
                .build();
        var textResponse = api.sendMessage(contact, video)
                .join();
        log("Sent video: %s", textResponse);
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    @Order(32)
    public void testGifBuilder() {
        log("Sending gif...");
        var video = VideoMessage.newGifBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes("http://techslides.com/demos/sample-videos/small.mp4"))
                .caption("Gif")
                .build();
        var textResponse = api.sendMessage(contact, video)
                .join();
        log("Sent video: %s", textResponse);
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test
    @Order(33)
    public void testPdfBuilder() {
        log("Sending pdf...");
        var document = DocumentMessage.newDocumentBuilder()
                .mediaConnection(api.store().mediaConnection())
                .media(MediaUtils.readBytes("http://www.orimi.com/pdf-test.pdf"))
                .title("Pdf test")
                .fileName("pdf-test.pdf")
                .pageCount(1)
                .build();
        var textResponse = api.sendMessage(contact, document)
                .join();
        log("Sent pdf: %s", textResponse);
    }

    @Test
    @Order(34)
    public void testContactBuilder() {
        log("Sending contact message...");
        var vcard = buildVcard();
        var document = ContactMessage.newContactBuilder()
                .name("Test")
                .vcard(vcard)
                .build();
        var textResponse = api.sendMessage(contact, document)
                .join();
        log("Sent contact: %s", textResponse);
    }

    private String buildVcard() {
        return """
                BEGIN:VCARD
                VERSION:3.0
                N:%s
                FN:%s
                TEL;type=CELL:+%s
                END:VCARD
                """.formatted("Test", "Testing", contact.user());
    }

    @Test
    @Order(35)
    public void testLocationBuilder() {
        log("Sending location message...");
        var location = LocationMessage.newLocationBuilder()
                .latitude(40.730610)
                .longitude(-73.935242)
                .magneticNorthOffset(0)
                .build();
        var textResponse = api.sendMessage(contact, location)
                .join();
        log("Sent location: %s", textResponse);
    }

    @Test
    @Order(36)
    public void testGroupInviteBuilder() {
        if (group == null) {
            testGroupCreation();
        }

        log("Querying group invite code");
        var code = api.queryInviteCode(group)
                .join();
        log("Queried %s", code);

        log("Sending group invite message...");
        var invite = GroupInviteMessage.newGroupInviteBuilder()
                .groupId(group)
                .code(code)
                .expiration(ZonedDateTime.now()
                        .plusDays(3)
                        .toEpochSecond())
                .name(group.user())
                .build();
        var textResponse = api.sendMessage(contact, invite)
                .join();
        log("Sent invite: %s", textResponse);
    }

    @Test
    @Order(37)
    public void testEnableEphemeralMessages() {
        if (group == null) {
            testGroupCreation();
        }

        log("Enabling ephemeral messages...");
        var ephemeralResponse = api.changeEphemeralTimer(group, ChatEphemeralTimer.ONE_WEEK)
                .join();
        log("Enabled ephemeral messages: %s", ephemeralResponse);
    }

    @Test
    @Order(38)
    public void testDisableEphemeralMessages() {
        if (group == null) {
            testGroupCreation();
        }

        log("Disabling ephemeral messages...");
        var ephemeralResponse = api.changeEphemeralTimer(group, ChatEphemeralTimer.OFF)
                .join();
        log("Disabled ephemeral messages: %s", ephemeralResponse);
    }

    @Test
    @Order(39)
    public void testLeave() {
        if (group == null) {
            testGroupCreation();
        }

        log("Leaving group...");
        var ephemeralResponse = api.leave(group)
                .join();
        log("Left group: %s", ephemeralResponse);
    }

    @Test
    @Order(39)
    public void testClearChat() {
        log("Clearing chat...");
        var ephemeralResponse = api.clear(contact, false)
                .join();
        log("Cleared chat: %s", ephemeralResponse);
    }

    @Test
    @Order(40)
    @SneakyThrows
    public void testDeleteChat() {
        log("Deleting chat...");
        var ephemeralResponse = api.delete(contact)
                .join();
        log("Deleted chat: %s", ephemeralResponse);
    }

    @SuppressWarnings("JUnit3StyleTestMethodInJUnit4Class")
    @AfterAll
    public void testDisconnect() {
        log("Logging off...");
        CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES)
                .execute(api::disconnect);
        api.await();
        log("Logged off");
    }

    @Override
    public void onNewMessage(MessageInfo info) {
        System.out.printf("New message: %s%n", info);
    }

    @Override
    public void onChatMessages(Chat chat, boolean last) {
        if (!last) {
            return;
        }

        System.out.printf("%s is ready with %s messages%n", chat.name(), chat.messages()
                .size());
    }


    private void log(String message, Object... params) {
        System.out.printf(message + "%n", redactParameters(params));
    }

    private Object[] redactParameters(Object... params) {
        if (!GithubActions.isActionsEnvironment()) {
            return params;
        }

        return Arrays.stream(params)
                .map(entry -> "***")
                .toArray(String[]::new);
    }
}
