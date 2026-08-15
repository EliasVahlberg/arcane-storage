package arcanestorage.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Nothing in the terminal container may dereference {@code terminal} without establishing that it is there.
 *
 * <p>Since the wireless terminal exists, {@code terminal} is null on a remote client: that container's client half
 * has no object entity, and cannot -- the entity lives on a level the client may not have loaded. Anything running
 * on both sides therefore has to ask.
 *
 * <p><b>This has now been the same bug three times, which is the argument for a test rather than for care.</b> A
 * container action's {@code executePacket} runs on the clicking client as well as the server, because
 * {@code runAndSendAction} sends the packet and then executes locally; the first two were a
 * {@code ClassCastException} from {@code getServerClient()}, and the third was
 * {@code terminal.getLevel()} inside {@code WithdrawAction}, which threw the moment an item was taken out through
 * a wireless terminal. All three passed the entire test suite first, and they always will: the Python suite drives
 * a real server with a synthetic player and <b>no client at all</b>, so no client-side branch is ever taken.
 *
 * <p>The rule is that a member dereferencing {@code terminal} must also, somewhere in the same member, mention
 * {@code isServer} or compare {@code terminal} to null. Deliberately about that one field rather than about the
 * word "null" anywhere in the body: a first attempt at the sibling rule in {@link ContainerActionGuardTest} was
 * written, found to be satisfied by the crashing code -- which contained {@code client == null} on a different
 * line from the dereference -- and deleted. A test that cannot fail is worse than no test.
 */
public class TerminalNullGuardTest {

   private static final Path SOURCE =
      Paths.get("src/main/java/arcanestorage/container/StorageTerminalContainer.java");

   /** A method, constructor or nested-class member declaration, up to its opening brace. */
   private static final Pattern MEMBER = Pattern.compile(
      "(?m)^\\s{3,9}(?:public|protected|private)(?:\\s+static)?(?:\\s+final)?[^;={}]*\\)\\s*\\{");

   /** {@code terminal.something}, however it is qualified, but not {@code terminal == null} and not a field. */
   private static final Pattern DEREFERENCE = Pattern.compile("\\bterminal\\.\\w");

   @Test
   public void everyTerminalDereferenceEstablishesThatItIsThere() throws IOException {
      assertTrue("the container moved -- this test is looking in the wrong place", Files.isRegularFile(SOURCE));

      String text = stripComments(new String(Files.readAllBytes(SOURCE), StandardCharsets.UTF_8));
      List<int[]> members = members(text);
      assertFalse("no members matched -- the pattern has stopped working", members.isEmpty());

      List<String> offenders = new ArrayList<>();
      Matcher matcher = DEREFERENCE.matcher(text);
      int checked = 0;

      while (matcher.find()) {
         int[] member = innermostContaining(members, matcher.start());
         if (member == null) {
            // A dereference outside any member is a field initialiser, which runs before any guard could.
            offenders.add("a field initialiser at offset " + matcher.start());
            continue;
         }

         checked++;
         String body = text.substring(member[0], member[1]);
         String declaration = declarationOf(text, member[0]);
         boolean guarded = body.contains("isServer")
            || body.contains("terminal != null")
            || body.contains("terminal == null")
            || body.contains("requireNonNull(terminal")

            // A member that is handed a ServerClient is on the server by construction, since there is no such
            // object on the other side to hand it. isValid and isWithinReach are the two, both called only from
            // ServerClient's own tick, and neither can be reached from a client at all.
            || declaration.contains("ServerClient");

         if (!guarded) {
            offenders.add("line " + lineOf(text, matcher.start()));
         }
      }

      assertTrue(
         "terminal is dereferenced without checking the side or the field. It is null on a remote client -- the "
            + "wireless terminal's client half has no object entity -- and container actions run on the client "
            + "too, since runAndSendAction executes locally after sending. Use level(), getTerminalName(), "
            + "getInstalledTechs() or getBuses(), which all answer on both sides. No other test can reach this: "
            + "the Python suite has no client. Offenders: " + offenders,
         offenders.isEmpty()
      );

      assertTrue("expected several dereferences to check, found " + checked, checked >= 5);
   }

   /** The declaration line preceding a body, which is where a {@code ServerClient} parameter would appear. */
   private static String declarationOf(String text, int openBraceIndex) {
      int start = text.lastIndexOf('\n', text.lastIndexOf('\n', openBraceIndex) - 1);
      return text.substring(Math.max(start, 0), openBraceIndex);
   }

   /** Every member declaration's body span, as {@code [start, end)} offsets. */
   private static List<int[]> members(String text) {
      List<int[]> found = new ArrayList<>();
      Matcher matcher = MEMBER.matcher(text);

      while (matcher.find()) {
         int open = text.lastIndexOf('{', matcher.end() - 1);
         int close = matchingBrace(text, open);
         found.add(new int[] {open, close});
      }

      return found;
   }

   /**
    * The smallest member span containing an offset.
    *
    * <p>Smallest, not first, because a nested class's methods sit inside the outer class's own members in the
    * text. The container's actions are nested classes, and taking the outer span would let an outer method's
    * guard excuse an inner method that has none -- which is precisely the bug being looked for.
    */
   private static int[] innermostContaining(List<int[]> members, int offset) {
      int[] best = null;

      for (int[] member : members) {
         if (offset > member[0] && offset < member[1]) {
            if (best == null || member[1] - member[0] < best[1] - best[0]) {
               best = member;
            }
         }
      }

      return best;
   }

   private static int matchingBrace(String text, int openBraceIndex) {
      int depth = 0;

      for (int i = openBraceIndex; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '{') {
            depth++;
         } else if (c == '}') {
            depth--;
            if (depth == 0) {
               return i + 1;
            }
         }
      }

      return text.length();
   }

   private static int lineOf(String text, int offset) {
      int line = 1;
      for (int i = 0; i < offset; i++) {
         if (text.charAt(i) == '\n') {
            line++;
         }
      }

      return line;
   }

   /**
    * Removes comments before matching.
    *
    * <p>Both directions matter. A comment naming {@code terminal.getLevel()} -- and the fix for this very bug
    * left one behind, explaining what used to be there -- would be reported as an unguarded dereference; and a
    * comment mentioning {@code isServer} would excuse a member that never checks it. The sibling test learned the
    * second half of that the hard way. Line numbers stay usable because the text is replaced, not removed.
    */
   private static String stripComments(String text) {
      StringBuilder out = new StringBuilder(text.length());
      int i = 0;

      while (i < text.length()) {
         if (text.startsWith("/*", i)) {
            int end = text.indexOf("*/", i);
            end = end < 0 ? text.length() : end + 2;
            for (int j = i; j < end; j++) {
               out.append(text.charAt(j) == '\n' ? '\n' : ' ');
            }

            i = end;
         } else if (text.startsWith("//", i)) {
            int end = text.indexOf('\n', i);
            end = end < 0 ? text.length() : end;
            for (int j = i; j < end; j++) {
               out.append(' ');
            }

            i = end;
         } else {
            out.append(text.charAt(i));
            i++;
         }
      }

      return out.toString();
   }
}
