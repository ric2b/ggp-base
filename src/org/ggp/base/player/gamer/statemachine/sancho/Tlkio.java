package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class for logging to tlk.io.
 */
public class Tlkio implements Runnable
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final Pattern TOKEN_PATTERN = Pattern.compile("content=\"([^\"]+)\" name=\"csrf-token\"");
  private static final Pattern CHANNEL_PATTERN = Pattern.compile(".chat_id = '(\\d+)'");

  private final String mNickname;
  private final String mChannelName;

  private String mChannelID;
  private String mCookie;
  private String mToken;

  private final BlockingQueue<String> mMessageQueue = new ArrayBlockingQueue<>(5);

  private final Thread mThread;

  /**
   * Create a new connection to tlk.io.
   *
   * @param xiNickname - the nickname to use.
   * @param xiChannel - the chat channel to connect to.
   */
  public Tlkio(String xiNickname, String xiChannel)
  {
    mNickname = xiNickname;
    mChannelName = xiChannel;

    mThread = new Thread(this, "Tlkio");
    mThread.setDaemon(true);
    mThread.start();
  }

  /**
   * Broadcast a message.  (You must login first.)
   *
   * @param xiMessage - the message
   */
  public void broadcast(String xiMessage)
  {
    // Add the message to the queue of messages.  If full, we'll just drop it.
    mMessageQueue.offer(xiMessage);
  }

  /**
   * Close the connection to tlk.io and tidy up.
   */
  public void stop()
  {
    mThread.interrupt();
    try
    {
      mThread.join(5000);
    }
    catch (InterruptedException lEx)
    {
      LOGGER.warn("Interrupted whilst waiting for Tlkio to stop");
    }

    if (mThread.isAlive())
    {
      LOGGER.error("Failed to stop Tlkio thread");
    }

    mMessageQueue.clear();
  }

  private void getChannelParams(String xiChannel) throws IOException
  {
    // Fetch the channel page.
    URL lURL = new URL("https://tlk.io/" + xiChannel);
    HttpsURLConnection lConnection = (HttpsURLConnection)lURL.openConnection();

    int lResponseCode = lConnection.getResponseCode();

    // Extract the cookie from the headers.
    extractCookie(lConnection);

    // Read the body.
    String lBody = readBody(lConnection.getInputStream());

    // Extract the token from the body.
    Matcher lMatcher = TOKEN_PATTERN.matcher(lBody);
    lMatcher.find();
    mToken = lMatcher.group(1);

    // Extract the channel ID from the body.
    lMatcher = CHANNEL_PATTERN.matcher(lBody);
    lMatcher.find();
    mChannelID = lMatcher.group(1);
  }

  private void extractCookie(HttpsURLConnection xiConnection) throws IOException
  {
    String lCookie = xiConnection.getHeaderField("Set-Cookie");
    if (lCookie == null)
    {
      LOGGER.warn("No cookie in response");
      return;
    }
    lCookie = lCookie.substring(0, lCookie.indexOf(';'));
    mCookie = lCookie;
  }

  /**
   * Login to tlk.io.
   *
   * @return whether login was successful.
   */
  private boolean login()
  {
    try
    {
      // Get the parameters we need for logging to this channel and set our name.
      getChannelParams(mChannelName);
      sendPost("https://tlk.io/api/participant", "{\"nickname\":\"" + mNickname + "\"}");
      return true;
    }
    catch (IOException lEx)
    {
      LOGGER.warn("Failed to login to tlk.io");
    }

    return false;
  }

  private String readBody(InputStream xiInputStream) throws IOException
  {
    // Read the body.
    BufferedReader lResponseStream = new BufferedReader(new InputStreamReader(xiInputStream));
    String lInputLine;
    StringBuffer lResponseBuffer = new StringBuffer();

    while ((lInputLine = lResponseStream.readLine()) != null)
    {
      lResponseBuffer.append(lInputLine);
    }
    lResponseStream.close();
    String lBody = lResponseBuffer.toString();
    return lBody;
  }

  private void sendPost(String xiURL, String xiBody) throws IOException
  {
    // Create a connection.
    URL lURL = new URL(xiURL);
    HttpsURLConnection lConnection = (HttpsURLConnection)lURL.openConnection();

    // Use POST for new messages.
    lConnection.setRequestMethod("POST");

    // Add request headers.
    lConnection.setRequestProperty("Content-Type", "application/json");
    lConnection.setRequestProperty("Cookie", mCookie);
    lConnection.setRequestProperty("X-CSRF-Token", mToken);

    LOGGER.debug("Sending " + xiBody + " to " + xiURL + " with token " + mToken + " and cookie " + mCookie);

    // Send post request
    lConnection.setDoOutput(true);
    DataOutputStream lBodyWriter = new DataOutputStream(lConnection.getOutputStream());
    lBodyWriter.writeBytes(xiBody);
    lBodyWriter.flush();
    lBodyWriter.close();

    LOGGER.debug("Fetching: " + lConnection.getURL());
    int lResponseCode = lConnection.getResponseCode();
    if (lResponseCode != 200)
    {
      // Locally log details of failure to send to tlk.io.
      LOGGER.warn("tlk.io returned " + lResponseCode);
      String lBody = readBody(lConnection.getErrorStream());
      LOGGER.warn("Error body: " + lBody);
    }
    else
    {
      extractCookie(lConnection);
      String lBody = readBody(lConnection.getInputStream());
      LOGGER.debug("Success body: " + lBody);
    }
  }

  @Override
  public void run()
  {
    if (!login())
    {
      LOGGER.warn("Failed to login to tlk.io.  No messages will be broadcast for this match.");
      return;
    }

    try
    {
      // Until interrupted, keep taking messages off the queue and broadcasting them.
      String lURL = "https://tlk.io/api/chats/" + mChannelID + "/messages";
      while (true)
      {
        String lMessage = mMessageQueue.take();

        try
        {
          String lBody = "{\"body\":\"" + lMessage + "\",\"expired\":false}";
          sendPost(lURL, lBody);
        }
        catch (IOException lEx)
        {
          LOGGER.warn("IOException whilst broadcasting to tlk.io", lEx);
        }
      }
    }
    catch (InterruptedException lEx)
    {
      // We've been interrupted.  Terminate.
    }
  }
}
