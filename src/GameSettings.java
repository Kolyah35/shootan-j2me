import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;

public final class GameSettings {
    private static final String STORE_NAME = "shootan";
    private static final int MAGIC = STORE_NAME.hashCode();
    private static final int VERSION = 1;

    public String username = null;
    public String hostname = "sffempire.ru";
    public byte hat = 0;
    public int targetFpsChoice = 1;
    public boolean debug = false;
    public boolean syncAimDirection = true;

    public static GameSettings load() {
        GameSettings result = new GameSettings();

        RecordStore store = null;
        RecordEnumeration entries = null;

        try {
            store = RecordStore.openRecordStore(STORE_NAME, false);
            entries = store.enumerateRecords(null, null, false);
            if (!entries.hasNextElement()) return result;

            byte[] bytes = store.getRecord(entries.nextRecordId());
            if (bytes == null) return result;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            try {
                if (in.readInt() != MAGIC) return result;
                if (in.readUnsignedByte() != VERSION) return result;

                String user = in.readUTF();
                String host = in.readUTF();
                int hat = in.readUnsignedByte();
                int fps = in.readUnsignedByte();
                boolean showDebug = in.readBoolean();
                boolean syncAim = in.readBoolean();

                if (user.length() <= 32 && user.length() > 0) result.username = user;
                if (host.length() > 0 && host.length() <= 256) result.hostname = host;
                if (hat >= 0 && hat <= 3) result.hat = (byte) hat;
                if (fps >= 0 && fps <= 2) result.targetFpsChoice = fps;
                result.debug = showDebug;
                result.syncAimDirection = syncAim;
            } finally {
                in.close();
            }
        } catch (RecordStoreNotFoundException notSavedYet) {
        } catch (Exception e) {
            System.out.println("Cannot load RMS settings: " + e.toString());
        } finally {
            if (entries != null) entries.destroy();
            if (store != null) {
                try { store.closeRecordStore(); }
                catch (RecordStoreException ignored) { }
            }
        }
        return result;
    }

    public synchronized void save() throws IOException, RecordStoreException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeUTF(username == null ? "" : username);
        out.writeUTF(hostname == null ? "" : hostname);
        out.writeByte(hat);
        out.writeByte(targetFpsChoice);
        out.writeBoolean(debug);
        out.writeBoolean(syncAimDirection);
        out.flush();
        byte[] data = bytes.toByteArray();
        out.close();

        RecordStore store = null;
        RecordEnumeration entries = null;
        try {
            store = RecordStore.openRecordStore(STORE_NAME, true);
            entries = store.enumerateRecords(null, null, false);
            if (entries.hasNextElement()) {
                int id = entries.nextRecordId();
                store.setRecord(id, data, 0, data.length);
            } else {
                store.addRecord(data, 0, data.length);
            }
        } finally {
            if (entries != null) entries.destroy();
            if (store != null) store.closeRecordStore();
        }
    }
}
