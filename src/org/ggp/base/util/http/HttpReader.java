
package org.ggp.base.util.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.HashMap;

public final class HttpReader
{
  /**
   * A GGP request.
   */
  public static class GGPRequest
  {
    /**
     * The GGP request itself.
     */
    public String mRequest;

    /**
     * The GGP-related HTTP headers sent with the request.
     */
    public final HashMap<String, String> mHeaders = new HashMap<>();
  }

  // Wrapper methods to support socket timeouts for reading requests/responses.

  public static String readAsClient(Socket socket, int timeout)
      throws IOException, SocketTimeoutException
  {
    socket.setSoTimeout(timeout);
    return readAsClient(socket);
  }

  public static GGPRequest readAsServer(Socket socket, int timeout)
      throws IOException, SocketTimeoutException
  {
    socket.setSoTimeout(timeout);
    return readAsServer(socket);
  }

  // Implementations of reading HTTP responses (readAsClient) and
  // HTTP requests (readAsServer) for the purpose of communicating
  // with other general game playing systems.

  public static String readAsClient(Socket socket) throws IOException
  {
    BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    return readContentFromPOST(br).mRequest;
  }

  /**
   * Read a request from the socket.
   *
   * @param xiSocket - the socket.
   *
   * @return the GGP request (without headers).
   *
   * @throws IOException if there was a problem reading the request.
   */
  public static String readRequestAsServer(Socket xiSocket) throws IOException
  {
    return readAsServer(xiSocket).mRequest;
  }

  /**
   * Read a request from the socket.
   *
   * @param xiSocket - the socket.
   *
   * @return the GGP request along with any GGP header..
   *
   * @throws IOException if there was a problem reading the request.
   */
  public static GGPRequest readAsServer(Socket socket) throws IOException
  {
    GGPRequest lRequest = new GGPRequest();

    BufferedReader lReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

    // The first line of the HTTP request is the request line.
    String requestLine = lReader.readLine();
    if (requestLine.toUpperCase().startsWith("GET "))
    {
      String lMessage = requestLine.substring(5, requestLine.lastIndexOf(' '));
      lMessage = URLDecoder.decode(lMessage, "UTF-8");
      lMessage = lMessage.replace((char)13, ' ');
      lRequest.mRequest = lMessage;

      parseHeaders(lReader, lRequest);
    }
    else if (requestLine.toUpperCase().startsWith("POST "))
    {
      lRequest = readContentFromPOST(lReader);
    }
    else if (requestLine.toUpperCase().startsWith("OPTIONS "))
    {
      // Web browsers can send an OPTIONS request in advance of sending
      // real XHR requests, to discover whether they should have permission
      // to send those XHR requests. We want to handle this at the network
      // layer rather than sending it up to the actual player, so we write
      // a blank response (which will include the headers that the browser
      // is interested in) and throw an exception so the player ignores the
      // rest of this request.
      HttpWriter.writeAsServer(socket, "");
      throw new IOException("Drop this message at the network layer.");
    }
    else
    {
      HttpWriter.writeAsServer(socket, "");
      throw new IOException("Unexpected request type: " + requestLine);
    }

    return lRequest;
  }

  private static GGPRequest readContentFromPOST(BufferedReader br) throws IOException
  {
    GGPRequest lRequest = new GGPRequest();
    String line;
    int theContentLength = -1;
    StringBuilder theContent = new StringBuilder();
    while ((line = br.readLine()) != null)
    {
      if (line.toLowerCase().startsWith("content-length:"))
      {
        try
        {
          theContentLength = Integer.parseInt(line.toLowerCase().replace("content-length:", "").trim());
        }
        catch (NumberFormatException e)
        {
          throw new IOException("Content-Length header can't be parsed: \"" + line + "\"");
        }
      }
      else if (line.length() == 0)
      {
        // We want to ignore the headers in the request, so we'll just
        // ignore every line up until the first blank line. The content
        // of the request appears after that.
        if (theContentLength != -1)
        {
          // When the content-length header is available, we only read exactly
          // that much content, once we reach the content.
          for (int i = 0; i < theContentLength; i++)
          {
            theContent.append((char)br.read());
          }
          lRequest.mRequest = theContent.toString().trim();
          return lRequest;
        }
        throw new IOException("Could not find Content-Length header.");
      }
      else
      {
        parseHeader(line, lRequest);
      }
    }
    throw new IOException("Could not find content in POST request.");
  }

  /**
   * Parse the HTTP headers, storing any GGP-related ones in the request object.
   *
   * @param xiReader - an input reader.
   * @param xiRequest - the request object in which to store the headers.
   *
   * @throws IOException if there was a problem reading the headers.
   */
  private static void parseHeaders(BufferedReader xiReader, GGPRequest xiRequest) throws IOException
  {

    String lLine;
    while ((lLine = xiReader.readLine()) != null)
    {
      if (lLine.length() == 0)
      {
        // A blank line signifies the end of the headers.  We're all done.
        break;
      }

      // Attempt to parse the line as a header.
      parseHeader(lLine, xiRequest);
    }
  }

  /**
   * Attempt to parse a line as an HTTP header.  If successful, and it's a GGP header, add the details to the request
   * object.
   *
   * @param xiLine - the line to parse.
   * @param xiRequest - the request object in which to store the header if parsing is successful.
   */
  private static void parseHeader(String xiLine, GGPRequest xiRequest)
  {
    // If the header contains GGP, store it for logging.
    int lColonIndex = xiLine.indexOf(':');
    if ((lColonIndex != -1) && (lColonIndex < xiLine.length() - 1))
    {
      String lHeader = xiLine.substring(0, lColonIndex).trim();
      String lValue = xiLine.substring(lColonIndex + 1).trim();

      if (lHeader.toLowerCase().contains("ggp"))
      {
        xiRequest.mHeaders.put(lHeader, lValue);
      }
    }
  }
}