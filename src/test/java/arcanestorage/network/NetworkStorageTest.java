package arcanestorage.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import necesse.engine.localization.message.GameMessage;
import necesse.engine.localization.message.StaticMessage;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.inventory.Inventory;

/**
 * The membership seam, exercised by a type this mod does not know about.
 *
 * <p>That is the whole point of the test. Every other test proves the network works for the Storage
 * Unit, which proves nothing about a third-party member — and "another mod can join" is a claim that is
 * easy to make and easy to get wrong, because the compiler is satisfied by an interface nobody can
 * actually implement usefully.
 *
 * <p>{@code ForeignSilo} below is written as a mod author would have to write one: it implements
 * {@link NetworkStorage}, holds an {@link Inventory}, and knows nothing else about this mod. If this
 * test needs anything from {@code arcanestorage} beyond the interface, the seam is not a seam.
 */
public class NetworkStorageTest {

   /** A storage member from an imaginary other mod. No object entity: it is never asked for one here. */
   private static final class ForeignSilo implements NetworkStorage {

      private final Inventory inventory;
      private boolean online = true;

      ForeignSilo(int slots) {
         this.inventory = new Inventory(slots);
      }

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }

      @Override
      public GameMessage getInventoryName() {
         return new StaticMessage("Foreign Silo");
      }

      @Override
      public ObjectEntity getObjectEntity() {
         return null;
      }

      /** Overridden to prove the default is overridable: a member may leave without being broken. */
      @Override
      public boolean isOnNetwork() {
         return this.online;
      }
   }

   @Test
   public void aForeignMemberContributesItsSlots() {
      List<NetworkStorage> network = Arrays.asList(new ForeignSilo(20), new ForeignSilo(20));

      assertEquals("capacity is the sum over members, whoever wrote them", 40,
            NetworkContents.totalSlots(network));
      assertEquals("and nothing is used yet", 0, NetworkContents.usedSlots(network));
   }

   @Test
   public void aMemberMayDeclareItselfOffTheNetwork() {
      ForeignSilo silo = new ForeignSilo(20);

      assertTrue("the default is 'as long as it exists'", silo.isOnNetwork());

      silo.online = false;

      assertFalse("a member with a reason to drop out can say so, without being broken",
            silo.isOnNetwork());
   }

   @Test
   public void theSeamNeedsOnlyTheGamesOwnInventoryInterface() {
      ForeignSilo silo = new ForeignSilo(20);

      assertTrue("a member is an OEInventory, so vanilla's own slot type accepts it unchanged",
            silo instanceof necesse.entity.objectEntity.interfaces.OEInventory);
      assertTrue("and it inherits vanilla's answers rather than reimplementing them",
            silo.canQuickStackInventory() && silo.canRestockInventory() && silo.canSortInventory());
   }
}
