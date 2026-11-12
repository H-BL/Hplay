import android.net.wifi.WifiConfiguration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * Runs as uid 1000 (system) via `runconuid … app_process` and drives the hidden
 * `IWifiManager.getWifiApConfiguration` (48) / `setWifiApConfiguration` (49) transactions.
 *
 * On this ROM (MTK8666 / Android 9) the SoftAp band and channel live only in the framework
 * SoftAp configuration, which a normal app cannot write; uid 1000 is the identity the Wi-Fi
 * service accepts. The helper prints one `HOTSPOTAP:` line per fact so the caller can parse the
 * result and never report a fake success.
 *
 * Ops:
 *   get   <pkg>              -> HOTSPOTAP:GET band=.. channel=.. ssid=..
 *   set   <band> <pkg>       -> HOTSPOTAP:BEFORE … / SET ok=.. / AFTER …
 *   setbc <band> <ch> <pkg>  -> same lines as `set`, but writes apBand AND apChannel together
 *   ssid  <pkg> <ssid>       -> HOTSPOTAP:BEFORE … / SETSSID ok=.. / AFTER …
 */
public final class HotspotAp {
    private static final String DESCRIPTOR = "android.net.wifi.IWifiManager";
    private static final int TX_GET_AP_CONFIG = 48;
    private static final int TX_SET_AP_CONFIG = 49;

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                p("HOTSPOTAP:ERR usage");
                return;
            }
            String op = args[0];
            IBinder wifi = wifiBinder();
            if (wifi == null) {
                p("HOTSPOTAP:ERR no wifi service");
                return;
            }
            if (op.equals("get")) {
                WifiConfiguration config = getConfig(wifi, args.length > 1 ? args[1] : "android");
                if (config == null) {
                    p("HOTSPOTAP:ERR get failed");
                    return;
                }
                p("HOTSPOTAP:GET band=" + band(config) + " channel=" + channel(config) + " ssid=" + ssid(config));
                return;
            }
            if (op.equals("set")) {
                int band = Integer.parseInt(args[1]);
                String pkg = args.length > 2 ? args[2] : "android";
                WifiConfiguration config = getConfig(wifi, pkg);
                if (config == null) {
                    p("HOTSPOTAP:ERR get-before-set failed");
                    return;
                }
                p("HOTSPOTAP:BEFORE band=" + band(config) + " channel=" + channel(config) + " ssid=" + ssid(config));
                setInt(config, "apBand", band);
                p("HOTSPOTAP:SET ok=" + setConfig(wifi, config, pkg));
                WifiConfiguration after = getConfig(wifi, pkg);
                if (after != null) {
                    p("HOTSPOTAP:AFTER band=" + band(after) + " channel=" + channel(after) + " ssid=" + ssid(after));
                }
                return;
            }
            if (op.equals("setbc")) {
                int band = Integer.parseInt(args[1]);
                int ch = Integer.parseInt(args[2]);
                String pkg = args.length > 3 ? args[3] : "android";
                WifiConfiguration config = getConfig(wifi, pkg);
                if (config == null) {
                    p("HOTSPOTAP:ERR get-before-set failed");
                    return;
                }
                p("HOTSPOTAP:BEFORE band=" + band(config) + " channel=" + channel(config) + " ssid=" + ssid(config));
                setInt(config, "apBand", band);
                setInt(config, "apChannel", ch);
                p("HOTSPOTAP:SET ok=" + setConfig(wifi, config, pkg));
                WifiConfiguration after = getConfig(wifi, pkg);
                if (after != null) {
                    p("HOTSPOTAP:AFTER band=" + band(after) + " channel=" + channel(after) + " ssid=" + ssid(after));
                }
                return;
            }
            if (op.equals("ssid")) {
                String pkg = args.length > 1 ? args[1] : "android";
                String name = args.length > 2 ? args[2] : "";
                WifiConfiguration config = getConfig(wifi, pkg);
                if (config == null) {
                    p("HOTSPOTAP:ERR get-before-set failed");
                    return;
                }
                p("HOTSPOTAP:BEFORE band=" + band(config) + " channel=" + channel(config) + " ssid=" + ssid(config));
                setObject(config, "SSID", name);
                p("HOTSPOTAP:SETSSID ok=" + setConfig(wifi, config, pkg));
                WifiConfiguration after = getConfig(wifi, pkg);
                if (after != null) {
                    p("HOTSPOTAP:AFTER band=" + band(after) + " channel=" + channel(after) + " ssid=" + ssid(after));
                }
                return;
            }
            p("HOTSPOTAP:ERR unknown op " + op);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            p("HOTSPOTAP:ERR " + sw.toString().replace('\n', ' '));
        }
    }

    private static IBinder wifiBinder() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "wifi");
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Parcelable.Creator<WifiConfiguration> creator() throws Exception {
        return (Parcelable.Creator<WifiConfiguration>) f("CREATOR").get(null);
    }

    private static WifiConfiguration getConfig(IBinder wifi, String pkg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(pkg);
            wifi.transact(TX_GET_AP_CONFIG, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                return null;
            }
            return creator().createFromParcel(reply);
        } catch (Throwable t) {
            p("HOTSPOTAP:ERR get " + t);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static boolean setConfig(IBinder wifi, WifiConfiguration config, String pkg) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean ok = false;
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1);
            config.writeToParcel(data, 0);
            data.writeString(pkg);
            wifi.transact(TX_SET_AP_CONFIG, data, reply, 0);
            reply.readException();
            ok = reply.readInt() != 0;
            return ok;
        } catch (Throwable t) {
            p("HOTSPOTAP:ERR set " + t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static Field f(String name) {
        try {
            return WifiConfiguration.class.getField(name);
        } catch (NoSuchFieldException e) {
            try {
                Field declared = WifiConfiguration.class.getDeclaredField(name);
                declared.setAccessible(true);
                return declared;
            } catch (NoSuchFieldException e2) {
                throw new RuntimeException(e2);
            }
        }
    }

    private static int band(WifiConfiguration config) {
        try {
            return f("apBand").getInt(config);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int channel(WifiConfiguration config) {
        try {
            return f("apChannel").getInt(config);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object ssid(WifiConfiguration config) {
        try {
            return f("SSID").get(config);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setInt(WifiConfiguration config, String name, int value) throws Exception {
        f(name).setInt(config, value);
    }

    private static void setObject(WifiConfiguration config, String name, Object value) throws Exception {
        f(name).set(config, value);
    }

    private static void p(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
