package arcanestorage.object;

import java.awt.Color;

import arcanestorage.ArcaneStorage;
import arcanestorage.objectentity.ImportBusObjectEntity;
import necesse.entity.objectEntity.ObjectEntity;
import necesse.level.maps.Level;

/** Places an {@link ImportBusObjectEntity}: chest in, network out. */
public class ImportBusObject extends BusObject {

   /** Greener than the mod's violet, so the two buses are told apart at a glance on the map. */
   public ImportBusObject() {
      super(ArcaneStorage.IMPORT_BUS_STRING_ID, new Color(96, 140, 96));
   }

   @Override
   protected String tipKey() {
      return "arcanestorage_importbustip";
   }

   @Override
   public ObjectEntity getNewObjectEntity(Level level, int x, int y) {
      return new ImportBusObjectEntity(level, x, y);
   }
}
