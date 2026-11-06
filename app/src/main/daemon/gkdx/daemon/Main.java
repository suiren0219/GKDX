package gkdx.daemon;

import android.view.accessibility.AccessibilityNodeInfo;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gkdX 常驻无障碍守护进程（shell/root 身份，经 app_process 启动）。
 *
 * 复用 /system/framework/uiautomator.jar 的 UiAutomationShellWrapper 建立
 * shell 级 UiAutomation 连接（与 `uiautomator dump` 同一机制，但常驻）：
 * - 不需要在系统设置里开启任何无障碍服务（对应用不可见）
 * - 不向目标应用注入任何代码
 * - getRootInActiveWindow 一次 binder 调用即取到当前窗口树（~10-80ms），
 *   没有 JVM 重启、没有 waitForIdle，这是延迟低于逐次 uiautomator dump 的关键
 *
 * 协议（127.0.0.1:28733，行式）：
 *   PING        -> PONG
 *   DUMP        -> XML <n>\n<xml 字节> | EMPTY\n
 *   BYE         -> 关闭连接
 */
public class Main {

    private static final int PORT = 28733;
    private static final String LOG_FILE = "/data/local/tmp/gkdx_daemon.log";
    private static final int MAX_NODES = 4000;
    private static final int MAX_DEPTH = 120;

    private static Object wrapper;          // com.android.uiautomator.core.UiAutomationShellWrapper
    private static android.app.UiAutomation uiAutomation;
    private static final Object writeLock = new Object();
    private static final AtomicInteger nodeCount = new AtomicInteger();

    public static void main(String[] args) {
        try {
            Class<?> w = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
            wrapper = w.getDeclaredConstructor().newInstance();
            w.getMethod("connect").invoke(wrapper);
            uiAutomation = (android.app.UiAutomation) w.getMethod("getUiAutomation").invoke(wrapper);
        } catch (Throwable t) {
            log("init fail: " + t);
            System.exit(1);
            return;
        }
        log("daemon up, port=" + PORT);
        try {
            ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
            while (true) {
                Socket s;
                try {
                    s = ss.accept();
                } catch (Throwable t) {
                    log("accept fail: " + t);
                    break;
                }
                try {
                    handle(s);
                } catch (Throwable t) {
                    log("handle fail: " + t);
                }
                try { s.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log("fatal: " + t);
        }
    }

    private static void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.equals("PING")) {
                    write(out, "PONG\n".getBytes(StandardCharsets.UTF_8));
                } else if (cmd.equals("DUMP")) {
                    doDump(out);
                } else if (cmd.equals("BYE")) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 客户端断开属正常
        }
    }

    private static void doDump(OutputStream out) {
        AccessibilityNodeInfo root = null;
        for (int i = 0; i < 3 && root == null; i++) {
            try {
                root = uiAutomation.getRootInActiveWindow();
            } catch (Throwable t) {
                log("getRoot fail: " + t);
                reconnect();
            }
            if (root == null) {
                try { Thread.sleep(60); } catch (InterruptedException e) { return; }
            }
        }
        if (root == null) {
            write(out, "EMPTY\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        StringBuilder xml = new StringBuilder(1 << 16);
        xml.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n");
        xml.append("<hierarchy rotation=\"0\">\n");
        nodeCount.set(0);
        serialize(root, xml, 0, 0);
        xml.append("</hierarchy>\n");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                out.write(("XML " + bytes.length + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                log("write fail: " + e);
            }
        }
    }

    private static void serialize(AccessibilityNodeInfo node, StringBuilder sb, int depth, int index) {
        if (node == null || depth > MAX_DEPTH || nodeCount.get() > MAX_NODES) return;
        nodeCount.incrementAndGet();
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);

        sb.append("  ".repeat(Math.max(0, depth)));
        sb.append("<node index=\"").append(index).append('"');
        attr(sb, "text", node.getText());
        attr(sb, "resource-id", node.getViewIdResourceName());
        attr(sb, "class", node.getClassName());
        attr(sb, "package", node.getPackageName());
        attr(sb, "content-desc", node.getContentDescription());
        sb.append(" checkable=\"").append(node.isCheckable()).append('"');
        sb.append(" checked=\"").append(node.isChecked()).append('"');
        sb.append(" clickable=\"").append(node.isClickable()).append('"');
        sb.append(" enabled=\"").append(node.isEnabled()).append('"');
        sb.append(" focusable=\"").append(node.isFocusable()).append('"');
        sb.append(" focused=\"").append(node.isFocused()).append('"');
        sb.append(" scrollable=\"").append(node.isScrollable()).append('"');
        sb.append(" long-clickable=\"").append(node.isLongClickable()).append('"');
        sb.append(" password=\"").append(node.isPassword()).append('"');
        sb.append(" selected=\"").append(node.isSelected()).append('"');
        sb.append(" visible-to-user=\"").append(node.isVisibleToUser()).append('"');
        sb.append(" bounds=\"[").append(r.left).append(',').append(r.top)
          .append("][").append(r.right).append(',').append(r.bottom).append("\"]");

        int n = node.getChildCount();
        if (n <= 0) {
            sb.append("/>\n");
            return;
        }
        sb.append(">\n");
        for (int i = 0; i < n; i++) {
            try {
                serialize(node.getChild(i), sb, depth + 1, i);
            } catch (Throwable t) {
                // 子节点可能瞬间失效，跳过
            }
        }
        sb.append("  ".repeat(Math.max(0, depth)));
        sb.append("</node>\n");
    }

    private static void attr(StringBuilder sb, String name, CharSequence v) {
        sb.append(' ').append(name).append("=\"");
        if (v != null) {
            String s = v.toString()
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;")
                    .replace("\n", "&#10;").replace("\r", "&#13;");
            sb.append(s);
        }
        sb.append('"');
    }

    private static void reconnect() {
        try { wrapper.getClass().getMethod("disconnect").invoke(wrapper); } catch (Throwable ignored) {}
        try { wrapper.getClass().getMethod("connect").invoke(wrapper); } catch (Throwable t) {
            log("reconnect fail: " + t);
        }
    }

    private static void write(OutputStream out, byte[] bytes) {
        synchronized (writeLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                log("write fail: " + e);
            }
        }
    }

    private static synchronized void log(String msg) {
        try {
            File f = new File(LOG_FILE);
            if (f.exists() && f.length() > 20000) f.delete();
            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
            w.append(java.text.SimpleDateFormat.getInstance().format(new java.util.Date()))
             .append(' ').append(msg).append('\n');
            w.close();
        } catch (Throwable ignored) {}
    }
}
