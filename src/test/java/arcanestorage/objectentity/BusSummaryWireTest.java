package arcanestorage.objectentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import necesse.engine.network.Packet;
import necesse.engine.network.PacketReader;
import necesse.engine.network.PacketWriter;
import org.junit.Test;

/**
 * A bus summary must survive the wire unchanged, field for field.
 *
 * <p>Worth its own test because of where it is read. Summaries are appended to the wireless terminal's open packet
 * after the network's slot contents, and a reader that consumes one field fewer or one more than the writer wrote
 * does not fail there -- it silently misaligns everything that follows, so the symptom appears somewhere else
 * entirely, or nowhere until a field is added years later. Round-tripping is the cheapest way to keep the two
 * halves honest, and it needs no game loaded because a summary is ints, strings and an enum.
 *
 * <p>{@code equals} is tested alongside it because the remote mirror only sends the bus list when it differs from
 * the copy the client holds. An {@code equals} that ignored a field would mean a warning appearing on a bus and
 * never reaching the player looking at the Logistics tab from elsewhere.
 */
public class BusSummaryWireTest {

   private static BusSummary sample() {
      return new BusSummary(41, -7, true, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 2, "Ore in",
            5, "Ore out");
   }

   private static BusSummary roundTrip(BusSummary original) {
      Packet packet = new Packet();
      original.writePacket(new PacketWriter(packet));
      return BusSummary.readPacket(new PacketReader(packet));
   }

   @Test
   public void everyFieldSurvivesTheWire() {
      BusSummary original = sample();
      BusSummary copy = roundTrip(original);

      assertEquals(original.tileX, copy.tileX);
      assertEquals(original.tileY, copy.tileY);
      assertEquals(original.importing, copy.importing);
      assertEquals(original.state, copy.state);
      assertEquals(original.conflictItemID, copy.conflictItemID);
      assertEquals(original.conflictX, copy.conflictX);
      assertEquals(original.conflictY, copy.conflictY);
      assertEquals(original.ordinal, copy.ordinal);
      assertEquals(original.customName, copy.customName);
      assertEquals(original.conflictOrdinal, copy.conflictOrdinal);
      assertEquals(original.conflictCustomName, copy.conflictCustomName);

      // Asserted through equals as well, so this test fails rather than passing vacuously if equals is ever
      // weakened to identity.
      assertEquals(original, copy);
   }

   @Test
   public void aReaderStopsExactlyWhereTheWriterDid() {
      Packet packet = new Packet();
      PacketWriter writer = new PacketWriter(packet);
      sample().writePacket(writer);

      // A marker after the summary. If the reader consumes the wrong number of bytes, this is what a real packet
      // would lose -- the slot contents, or the next bus.
      writer.putNextInt(987654);

      PacketReader reader = new PacketReader(packet);
      BusSummary.readPacket(reader);
      assertEquals("the reader and writer disagree about the summary's length", 987654, reader.getNextInt());
   }

   @Test
   public void equalsNoticesEveryFieldThatChanges() {
      BusSummary base = sample();

      assertNotEquals(base, new BusSummary(42, -7, true, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 2,
            "Ore in", 5, "Ore out"));
      assertNotEquals(base, new BusSummary(41, -7, false, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 2,
            "Ore in", 5, "Ore out"));
      assertNotEquals(base, new BusSummary(41, -7, true, DeviceState.ACTIVE, "ironbar", 12, 13, 2,
            "Ore in", 5, "Ore out"));
      assertNotEquals(base, new BusSummary(41, -7, true, DeviceState.RULE_CONFLICT, "goldbar", 12, 13, 2,
            "Ore in", 5, "Ore out"));
      assertNotEquals(base, new BusSummary(41, -7, true, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 3,
            "Ore in", 5, "Ore out"));
      assertNotEquals(base, new BusSummary(41, -7, true, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 2,
            "Ore out", 5, "Ore out"));

      // The conflict partner's name counts too: the reason text names it, so the same bus reads differently to a
      // player when the bus it is fighting with is renamed.
      assertNotEquals(base, new BusSummary(41, -7, true, DeviceState.RULE_CONFLICT, "ironbar", 12, 13, 2,
            "Ore in", 5, "Something else"));
   }
}
