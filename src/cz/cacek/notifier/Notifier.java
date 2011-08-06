package cz.cacek.notifier;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Simple notification application which works as a HTTP server and uses system
 * tray to display notifications. It also plays a system sound on Windows OS.
 * 
 * @author Josef Cacek
 */
public class Notifier implements HttpHandler {
  private static final long serialVersionUID = 1L;

  public static final int DEFAULT_PORT = 8811;
  public static final String DEFAULT_SOUND_PROP = "win.sound.asterisk";
  public static final String DEFAULT_ICON = "sun";

  private final int port;
  private final String defaultIcon;

  private final SystemTray systemTray;
  private final TrayIcon icon;
  private final Runnable sound;

  private HttpServer httpServer;

  /**
   * Construtor.
   * 
   * @param aPort
   *          port number to bind the HTTP server; if not positive number then
   *          the {@link #DEFAULT_PORT} is used
   * @param aDefaultIcon
   *          default icon file-name (without ".png" extension); if null then
   *          then the {@link #DEFAULT_ICON} is used
   * @param aSoundProperty
   *          desktop property name with the notification sound (as Runnable);
   *          if null then the {@link #DEFAULT_SOUND_PROP} is used
   * @throws IllegalStateException
   *           System tray is not supported in the OS by this java version.
   * @throws IllegalArgumentException
   *           default icon image is not available
   */
  public Notifier(final int aPort, final String aDefaultIcon, final String aSoundProperty)
      throws IllegalStateException, IllegalArgumentException {
    if (!SystemTray.isSupported()) {
      throw new IllegalStateException("System tray not supported!");
    }
    final Toolkit tk = Toolkit.getDefaultToolkit();

    this.port = aPort > 0 ? aPort : DEFAULT_PORT;
    this.systemTray = SystemTray.getSystemTray();
    final Object soundObject = tk.getDesktopProperty(aSoundProperty != null ? aSoundProperty : DEFAULT_SOUND_PROP);
    this.sound = (soundObject instanceof Runnable) ? (Runnable) soundObject : null;
    this.defaultIcon = aDefaultIcon != null ? aDefaultIcon : DEFAULT_ICON;
    final Image imgDefault = getImage(this.defaultIcon);
    if (imgDefault == null) {
      throw new IllegalArgumentException("Wrong default icon - " + this.defaultIcon);
    }

    this.icon = new TrayIcon(imgDefault);
    this.icon.setToolTip("Notifier on port " + this.port // 
        + "\n- click to remove events" // 
        + "\n- double-click to exit");
    this.icon.setImageAutoSize(true);
    this.icon.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          //double click to exit
          stop();
          System.exit(0);
        } else {
          //single click - return to the default icon
          icon.setImage(getImage(defaultIcon));
        }
        super.mouseClicked(e);
      }
    });
  }

  /**
   * HTTP request/response handler. If the requested path points to an existing
   * icon, then the system tray icon image is changed and the notification
   * message is retrieved from the request body. Empty response is returned with
   * status code either 200 (SC_OK - icon exists) or 404 (SC_NOT_FOUND - no such
   * icon).
   * 
   * @see com.sun.net.httpserver.HttpHandler#handle(com.sun.net.httpserver.HttpExchange)
   */
  public void handle(HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    int responseCode = 200; //OK
    final Image img = getImage(path);
    if (img != null) {
      final BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), "UTF-8"));
      final StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        if (sb.length() > 0)
          sb.append("\n");
        sb.append(line);
      }
      br.close();
      final String timeStr = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
      final String msgStr = sb.length() > 0 ? sb.toString() : "New notification arrived.";
      System.out.println(timeStr + " " + msgStr);

      icon.displayMessage("Notification " + timeStr, msgStr, MessageType.INFO);
      icon.setImage(img);
      if (sound != null)
        sound.run();

    } else {
      responseCode = 404; //Not found
    }
    exchange.sendResponseHeaders(responseCode, -1);
    exchange.getResponseBody().close();
  }

  /**
   * Starts the notifier (HTTP-server) and displays system tray icon.
   * 
   * @throws IOException
   *           HTTP server creation fails
   * @throws AWTException
   *           adding system tray icon fails
   */
  public synchronized void start() throws IOException, AWTException {
    if (httpServer != null) {
      stop();
    }
    httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpServer.createContext("/", this);
    httpServer.start();
    systemTray.add(icon);
  }

  /**
   * Stops the notifier (HTTP-server) and removes system tray icon.
   */
  public synchronized void stop() {
    if (httpServer == null)
      return;
    httpServer.stop(1);
    httpServer = null;
    systemTray.remove(icon);
  }

  /**
   * Main. Reads arguments from the command line, creates notifier with provided
   * (or default) parameters and starts the notifier. If an {@link Exception}
   * occures program is terminated with exit code -1.
   * 
   * @param args
   */
  public static void main(String[] args) {
    System.out.println("Usage:");
    System.out.println("$java -jar Notifier.jar [port  [defaultIcon [soundDesktopProperty]]");
    System.out.println();
    try {
      int port = 0;
      if (args.length > 0) {
        try {
          port = Integer.parseInt(args[0]);
        } catch (NumberFormatException nfe) {
          System.err.println("Wrong port number provided. Default will be used.");
        }
      }
      String defaultIcon = null;
      if (args.length > 1) {
        defaultIcon = args[1];
      }
      String soundProperty = null;
      if (args.length > 2) {
        soundProperty = args[2];
      }
      final Notifier notifier = new Notifier(port, defaultIcon, soundProperty);
      notifier.start();
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(-1);
    }
  }

  /**
   * Tries to load PNG image with the given name (path) from the Notifier's
   * classpath. Given name (path) is relative to the Notifier's package. Leading
   * slashes ('/') are removed from the name and suffix ".png" is appended.
   * 
   * @param name
   *          image name
   * @return PNG Image with given name or null if no such image exist.
   */
  private Image getImage(final String name) {
    if (name == null)
      return null;
    final URL url = getClass().getResource(name.replaceAll("^/*", "") + ".png");
    try {
      //check if the image exists
      url.openStream().close();
    } catch (IOException e) {
      return null;
    }
    return Toolkit.getDefaultToolkit().getImage(url);
  }
}
