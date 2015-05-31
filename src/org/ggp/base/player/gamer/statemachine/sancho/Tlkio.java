package org.ggp.base.player.gamer.statemachine.sancho;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

  /**
   * Login to tlk.io.
   *
   * @param xiChannel - the chat channel to connect to.
   *
   * @return whether the conncection succeeded.
   */
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
    // mToken = "Am7DG7Fil2sisUCYUz2qugiQaRMs4bgt8Sv71zqb2x8=";

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
    System.out.println(lCookie);
    lCookie = URLDecoder.decode(lCookie, "UTF-8");
    System.out.println(lCookie);
    lCookie = lCookie.replace("==", "");
    System.out.println(lCookie);
    mCookie = lCookie;

    // mCookie = "_tlkio_session=L0F3d0J2a1M5TnB5Q2E4ejRSRnArNTRVS2d1VnhwdGZVUUJOdW1OalpzTjdpcDFHM3lJWDBNWGtzWUhUUGhlM0YvRC8rZHU4bnZQMUI2QnhCdkhtZEhGQWE0VU52R2x0eXVhdHVUSjV0ZjlTSlU2b0FuSDlFdXNxNXZuTFJTQUFlZEdQVENGMUNsMEF5dEc5Tm52aTVTYytUeVdGeG1FbkJCWHRJRUJMMXpyTnBqTDJlTWNwUkczYzUwdGZnY1dYbDdQT25JaytaOHhCc01vblhxVmtMT3NnQmNoZmhabDh6WURpY0ttN1VEc0pvdCs0ZDVQRHZldjh6Q0UxM01lT29VZEdreEpuem0wNUtwOVdLc2o5TFE9PS0tUmFZMHNNcWN6UUROWFNxZGw2dy94Zz09--5cb35c2543e89dba4c78eb33298aa8aaddbdeb57";
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

    LOGGER.info("Sending " + xiBody + " to " + xiURL + " with token " + mToken + " and cookie " + mCookie);

    // Send post request
    lConnection.setDoOutput(true);
    DataOutputStream lBodyWriter = new DataOutputStream(lConnection.getOutputStream());
    lBodyWriter.writeBytes(xiBody);
    lBodyWriter.flush();
    lBodyWriter.close();

    LOGGER.info("Fetching: " + lConnection.getURL());
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
      LOGGER.info("Success body: " + lBody);
    }
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

  public static void main(String[] args) throws IOException
  {
    Tlkio lTlkio = new Tlkio();
    lTlkio.login("test");
    lTlkio.broadcast("testing");
  }
}
