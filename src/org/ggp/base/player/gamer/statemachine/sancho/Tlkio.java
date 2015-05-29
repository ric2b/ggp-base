package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Class for logging to tlk.io.
 */
public class Tlkio
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final Pattern TOKEN_PATTERN = Pattern.compile("content=\"([^\"]+)\" name=\"csrf-token\"");
  private static final Pattern CHANNEL_PATTERN = Pattern.compile(".chat_id = '(\\d+)'");

  private String mChannelID;
  private String mCookie;
  private String mToken;

  public boolean login(String xiChannel)
  {
    try
    {
      // Get the parameters we need for logging to this channel.
      getChannelParams(xiChannel);

      // Set our name.
      sendPost("https://tlk.io/api/participant", "{\"nickname\":\"Sancho\"}");

      return true;
    }
    catch (IOException lEx)
    {
      LOGGER.warn("Failed to login to tlk.io");
    }

    return false;
  }

  private void getChannelParams(String xiChannel) throws IOException
  {
    // Fetch the channel page.
    URL lURL = new URL("https://tlk.io/" + xiChannel);
    HttpsURLConnection lConnection = (HttpsURLConnection)lURL.openConnection();

    int lResponseCode = lConnection.getResponseCode();

    // Extract the cookie from the headers.
    String lCookie = lConnection.getHeaderField("Set-Cookie");
    lCookie = lCookie.substring(0, lCookie.indexOf(';'));
    System.out.println(lCookie);
    lCookie = URLDecoder.decode(lCookie, "UTF-8");
    System.out.println(lCookie);
    lCookie = lCookie.replace("==", "");
    System.out.println(lCookie);
    mCookie = lCookie;

    // Read the body.
    BufferedReader lResponseStream = new BufferedReader(new InputStreamReader(lConnection.getInputStream()));
    String lInputLine;
    StringBuffer lResponseBuffer = new StringBuffer();

    while ((lInputLine = lResponseStream.readLine()) != null)
    {
      lResponseBuffer.append(lInputLine);
    }
    lResponseStream.close();
    String lResponse = lResponseBuffer.toString();

    // Extract the token from the body.
    Matcher lMatcher = TOKEN_PATTERN.matcher(lResponse);
    lMatcher.find();
    mToken = lMatcher.group(1);

    // Extract the channel ID from the body.
    lMatcher = CHANNEL_PATTERN.matcher(lResponse);
    lMatcher.find();
    mChannelID = lMatcher.group(1);
  }

  /**
   * Broadcast a message.
   *
   * @param xiMessage - the message
   */
  public void broadcast(String xiMessage)
  {
    try
    {
      String lURL = "https://tlk.io/api/chats/" + mChannelID + "/messages";
      String lBody = "{\"body\":\"" + xiMessage + "\",\"expired\":false}";
      sendPost(lURL, lBody);
    }
    catch (IOException lEx)
    {
      LOGGER.warn("IOException whilst broadcasting to tlk.io", lEx);
    }
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

    System.out.println("Sending " + xiBody + " to " + xiURL + " with token " + mToken + " and cookie " + mCookie);

    // Send post request
    lConnection.setDoOutput(true);
    DataOutputStream lBodyWriter = new DataOutputStream(lConnection.getOutputStream());
    lBodyWriter.writeBytes(xiBody);
    lBodyWriter.flush();
    lBodyWriter.close();

    int lResponseCode = lConnection.getResponseCode();
    if (lResponseCode != 200)
    {
      // Locally log details of failure to send to tlk.io.
      LOGGER.warn("tlk.io returned " + lResponseCode);
    }
  }

  public static void main(String[] args) throws IOException
  {
    Tlkio lTlkio = new Tlkio();
    lTlkio.login("sancho");
    lTlkio.broadcast("test");
  }
}
