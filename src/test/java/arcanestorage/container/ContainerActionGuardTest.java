package arcanestorage.container;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * No container action may reach for server-only API without checking which side it is on.
 *
 * <p>This is a lint test over our own source rather than a behavioural one, and it exists because the
 * behaviour is <b>unreachable by every other test we have</b>. The Python suite drives a real server with a
 * synthetic player and no client at all, so {@code client.isServer()} is always true there and the client
 * branch of any container action is never taken. A missing guard therefore passes 208 tests and then throws the
 * first time a human clicks the button.
 *
 * <p>Which is not hypothetical. {@code ContainerCustomAction.runAndSendAction} sends the packet <i>and</i> then
 * calls {@code executePacket} locally, so the clicking client runs the server's code path too; on that side a
 * {@code NetworkClient} is a {@code ClientClient}, and {@code getServerClient()} throws a
 * {@code ClassCastException}. The upgrade panel shipped without the guard and did exactly that in game. The bus
 * panel's double-wrapped filter packet was the same gap from a different angle.
 *
 * <p><b>The rule is deliberately narrow, because the obvious wider one is wrong.</b> "Every action must return
 * early on the client" was tried first and immediately flagged three actions on the terminal that are correct:
 * {@code DepositAllAction}, {@code DepositCursorAction} and {@code WithdrawAction} work through container slots
 * and inventories, which exist and behave on both sides, and they have been exercised in game for weeks. An
 * action running on both sides is a legitimate design; an action calling {@code getServerClient()} on the client
 * is not, because that method is an unconditional cast.
 *
 * <p>So the check is: if the body mentions {@code getServerClient}, it must also mention {@code isServer}. That
 * asserts shape rather than behaviour, which is weaker than one would like and the only thing reachable from
 * here.
 */
public class ContainerActionGuardTest {

   /** Matches {@code public void executePacket(PacketReader ...) {} through to its closing brace. */
   private static final Pattern EXECUTE_PACKET =
      Pattern.compile("public void executePacket\\(PacketReader\\s+\\w+\\)\\s*\\{");

   @Test
   public void everyExecutePacketRefusesTheClient() throws IOException {
      List<Path> sources = sourceFiles();
      assertFalse("no sources found -- the test is looking in the wrong place", sources.isEmpty());

      List<String> offenders = new ArrayList<>();
      int checked = 0;

      for (Path source : sources) {
         String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
         Matcher matcher = EXECUTE_PACKET.matcher(text);

         while (matcher.find()) {
            checked++;
            String body = stripComments(methodBody(text, matcher.end() - 1));

            if (body.contains("getServerClient") && !body.contains("isServer")) {
               offenders.add(source.getFileName() + " at offset " + matcher.start());
            }
         }
      }

      assertTrue(
         "executePacket calls getServerClient() without checking the side. It runs on the client too -- "
            + "runAndSendAction sends the packet and then executes locally -- and there a NetworkClient is a "
            + "ClientClient, so that call is a ClassCastException. No other test can reach the branch: the "
            + "Python suite has no client. Add `if (!container.client.isServer()) { return; }`. Offenders: "
            + offenders,
         offenders.isEmpty()
      );

      // A guard against the test quietly matching nothing, which would make it permanently green.
      assertTrue("expected to find several container actions, found " + checked, checked >= 5);
   }

   private static List<Path> sourceFiles() throws IOException {
      Path root = Paths.get("src/main/java/arcanestorage");
      if (!Files.isDirectory(root)) {
         return new ArrayList<>();
      }

      try (Stream<Path> walk = Files.walk(root)) {
         List<Path> found = new ArrayList<>();
         walk.filter(path -> path.toString().endsWith(".java")).forEach(found::add);
         return found;
      }
   }

   /**
    * Removes comments, so only code is matched.
    *
    * <p>Not fastidiousness. Without this the test cannot fail: the guard it looks for is normally accompanied by
    * a comment explaining why the guard is there, that comment says {@code isServer}, and a plain text search is
    * then satisfied by the explanation of the code rather than the code. Found by deleting the real guard and
    * watching the test stay green.
    */
   private static String stripComments(String body) {
      return body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
   }

   /** The text between a method's opening brace and its matching close. */
   private static String methodBody(String text, int openBraceIndex) {
      int depth = 0;

      for (int i = openBraceIndex; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '{') {
            depth++;
         } else if (c == '}') {
            depth--;
            if (depth == 0) {
               return text.substring(openBraceIndex, i + 1);
            }
         }
      }

      return text.substring(openBraceIndex);
   }
}
