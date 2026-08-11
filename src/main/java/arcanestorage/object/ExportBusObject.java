package arcanestorage.object;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import arcanestorage.objectentity.ExportBusObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/** Places an {@link ExportBusObjectEntity}: network in, chest out. Inert until it has a rule. */
public class ExportBusObject extends BusObject {

   /** Amber against the import bus's green, since direction is the only thing to tell apart. */
   public ExportBusObject() {
      super(ArcaneStorage.EXPORT_BUS_STRING_ID, new Color(190, 140, 70));
   }

   @Override
   protected String tipKey() {
      return "arcanestorage_exportbustip";
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new ExportBusObjectEntity(level, x, y);
   }
}
