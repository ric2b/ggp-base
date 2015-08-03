package org.ggp.base.util.game;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.loader.RemoteResourceLoader;

import external.JSON.JSONException;
import external.JSON.JSONObject;

/**
 * Repository specifically design to load games from the Stanford "gamemaster" server.
 */
public class GamemasterGameRepository extends RemoteGameRepository
{
  public GamemasterGameRepository(String xiURL)
  {
    super(xiURL);
  }

  @Override
  protected Set<String> getUncachedGameKeys()
  {
    Set<String> lKeys = new HashSet<>();

    try
    {
      String lListing = RemoteResourceLoader.loadRaw(theRepoURL + "/games/");
      String[] lLines = lListing.split("\n");
      for (String lLine : lLines)
      {
        if (lLine.contains("folder.gif"))
        {
          int lStartIndex = lLine.indexOf("a href=\"");
          if (lStartIndex != -1)
          {
            lStartIndex += 8;
            int lEndIndex = lLine.indexOf("/\"", lStartIndex);
            if (lEndIndex != -1)
            {
              String lKey = lLine.substring(lStartIndex, lEndIndex);
              lKeys.add(lKey);
            }
          }
        }
      }
    }
    catch (IOException lEx)
    {
      System.err.println("Failed to read game list from gamemaster: " + lEx);
    }

    return lKeys;
  }

  @Override
  JSONObject getBundledMetadata()
  {
    // Gamemaster doesn't support bundled metadata.
    return null;
  }

  @Override
  protected String getGameURL(String theGameKey)
  {
    return theRepoURL + "/games/" + theGameKey + "/";
  }

  @Override
  protected String addVersionToGameURL(String theGameURL, int theVersion)
  {
    return theGameURL;
  }

  @Override
  protected JSONObject getGameMetadataFromRepository(String theGameURL)
      throws JSONException, IOException
  {
    String[] lSplitURL = theGameURL.split("/");
    String lKey = lSplitURL[lSplitURL.length - 1];

    JSONObject lJSON = new JSONObject();
    lJSON.put("version", 1); // Could use directory date/time.
    if (lKey.endsWith("bad"))
    {
      // Some games have split good/bad GDL.
      lJSON.put("rulesheet", "player.kif");
    }
    else
    {
      lJSON.put("rulesheet", lKey + ".kif");
    }
    lJSON.put("description", lKey + ".txt");
    return lJSON;
  }
}
