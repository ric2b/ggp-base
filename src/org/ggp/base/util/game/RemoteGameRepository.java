
package org.ggp.base.util.game;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.loader.RemoteResourceLoader;

import external.JSON.JSONArray;
import external.JSON.JSONException;
import external.JSON.JSONObject;

/**
 * Remote game repositories provide access to game resources stored on game
 * repository servers on the web. These require a network connection to work.
 *
 * @author Sam
 */
public class RemoteGameRepository extends GameRepository
{
  protected final String theRepoURL;

  public RemoteGameRepository(String theURL)
  {
    theRepoURL = properlyFormatURL(theURL);
  }

  @Override
  protected Set<String> getUncachedGameKeys()
  {
    Set<String> theGameKeys = new HashSet<>();
    try
    {
      JSONArray theArray = RemoteResourceLoader.loadJSONArray(theRepoURL + "/games/");
      for (int i = 0; i < theArray.length(); i++)
      {
        theGameKeys.add(theArray.getString(i));
      }
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
    return theGameKeys;
  }

  @Override
  protected Game getUncachedGame(String theKey)
  {
    return loadSingleGame(getGameURL(theKey));
  }

  public Game loadSingleGame(String theGameURL)
  {
    String[] theSplitURL = theGameURL.split("/");
    String theKey = theSplitURL[theSplitURL.length - 1];

    try
    {
      JSONObject theMetadata = getGameMetadataFromRepository(theGameURL);
      return loadSingleGameFromMetadata(theKey, theGameURL, theMetadata);
    }
    catch (JSONException e)
    {
      e.printStackTrace();
      return null;
    }
    catch (IOException e)
    {
      e.printStackTrace();
      return null;
    }
  }

  protected Game loadSingleGameFromMetadata(String theKey,
                                            String theGameURL,
                                            JSONObject theMetadata)
  {
    // Ensure that the game URL has a version.
    try
    {
      int theVersion = theMetadata.getInt("version");
      if (!isVersioned(theGameURL, theVersion))
      {
        theGameURL = addVersionToGameURL(theGameURL, theVersion);
      }
    }
    catch (JSONException e)
    {
      e.printStackTrace();
      return null;
    }

    String theName = null;
    try
    {
      theName = theMetadata.getString("gameName");
    }
    catch (JSONException e)
    {
    }

    String theDescription = getGameResourceFromMetadata(theGameURL, theMetadata, "description");
    String theStylesheet = getGameResourceFromMetadata(theGameURL, theMetadata, "stylesheet");
    String lRawRulesheet = getGameResourceFromMetadata(theGameURL, theMetadata, "rulesheet");
    String theRulesheet = null;
    if (lRawRulesheet != null)
    {
      theRulesheet = Game.preprocessRulesheet(lRawRulesheet);
    }

    if (theRulesheet == null || theRulesheet.isEmpty())
    {
      System.err.println("No rulesheet found for " + theKey);
      return null;
    }

    return new Game(theKey,
                    theName,
                    theDescription,
                    theGameURL,
                    theStylesheet,
                    theRulesheet);
  }

  JSONObject getBundledMetadata()
  {
    try
    {
      return RemoteResourceLoader.loadJSON(theRepoURL + "/games/metadata");
    }
    catch (JSONException e)
    {
      return null;
    }
    catch (IOException e)
    {
      return null;
    }
  }

  // ============================================================================================
  protected String getGameURL(String theGameKey)
  {
    return theRepoURL + "/games/" + theGameKey + "/";
  }

  protected String addVersionToGameURL(String theGameURL, int theVersion)
  {
    return theGameURL + "v" + theVersion + "/";
  }

  protected static boolean isVersioned(String theGameURL, int theVersion)
  {
    return theGameURL.endsWith("/v" + theVersion + "/");
  }

  protected JSONObject getGameMetadataFromRepository(String theGameURL)
      throws JSONException, IOException
  {
    return RemoteResourceLoader.loadJSON(theGameURL);
  }

  protected static String getGameResourceFromMetadata(String theGameURL,
                                                      JSONObject theMetadata,
                                                      String theResource)
  {
    try
    {
      String theResourceFile = theMetadata.getString(theResource);
      return RemoteResourceLoader.loadRaw(theGameURL + theResourceFile);
    }
    catch (Exception e)
    {
      return null;
    }
  }

  public static String properlyFormatURL(String theURL)
  {
    // Ensure the URL begins with a protocol.
    if (!theURL.startsWith("http://"))
    {
      theURL = "http://" + theURL;
    }

    // Strip any trailing '/'.
    if (theURL.endsWith("/"))
    {
      theURL = theURL.substring(0, theURL.length() - 1);
    }

    return theURL;
  }
}