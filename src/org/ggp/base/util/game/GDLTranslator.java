package org.ggp.base.util.game;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.symbol.grammar.Symbol;
import org.ggp.base.util.symbol.grammar.SymbolAtom;
import org.ggp.base.util.symbol.grammar.SymbolList;
import org.ggp.base.util.symbol.grammar.SymbolPool;

/**
 * Class to unscramble GDL that has been scrambled by the Tiltyard.
 */
public class GDLTranslator
{
  private static final int MAX_GDL_SIZE = 5000;

  private static final Logger LOGGER = LogManager.getLogger();

  private static final File LEARNING_DIR   = new File("data", "games");
  private static final String GDL_FILENAME = "gdl.txt";

  private final Map<SymbolAtom, SymbolAtom> mInternalToNetwork;
  private final Map<SymbolAtom, SymbolAtom> mNetworkToInternal;

  private File mGameDir;

  /**
   * Create an unscrambler for the specified rulesheet.
   *
   * @param xiRulesheet - the rulesheet.
   */
  public GDLTranslator(SymbolList xiRulesheet)
  {
    // Produce a rule mapping from the internal (saved) form to the network(current) form.
    mInternalToNetwork = findMapping(xiRulesheet);

    // Produce the reverse mapping.
    mNetworkToInternal = new HashMap<>();
    for (Entry<SymbolAtom, SymbolAtom> lEntry : mInternalToNetwork.entrySet())
    {
      mNetworkToInternal.put(lEntry.getValue(), lEntry.getKey());
    }
  }

  /**
   * Convert a symbol from internal to network format.
   *
   * @param xiInternal - the internal format.
   * @return the network format.
   */
  public Symbol internalToNetwork(Symbol xiInternal)
  {
    Symbol lNetwork;

    if (xiInternal instanceof SymbolAtom)
    {
      lNetwork = mInternalToNetwork.get(xiInternal);
      if (lNetwork == null)
      {
        lNetwork = xiInternal;
      }
    }
    else
    {
      SymbolList lInternalList = (SymbolList)xiInternal;
      List<Symbol> lNetworkList = new LinkedList<>();

      for (int lii = 0; lii < lInternalList.size(); lii++)
      {
        lNetworkList.add(internalToNetwork(lInternalList.get(lii)));
      }
      lNetwork = new SymbolList(lNetworkList);
    }

    return lNetwork;
  }

  /**
   * Convert a symbol from network to internal format.
   *
   * @param xiNetwork - the network format.
   * @return the internal format.
   */
  public Symbol networkToInternal(Symbol xiNetwork)
  {
    Symbol lInternal;

    if (xiNetwork instanceof SymbolAtom)
    {
      lInternal = mNetworkToInternal.get(xiNetwork);
      if (lInternal == null)
      {
        lInternal = xiNetwork;
      }
    }
    else
    {
      SymbolList lNetworkList = (SymbolList)xiNetwork;
      List<Symbol> lInternalList = new LinkedList<>();

      for (int lii = 0; lii < lNetworkList.size(); lii++)
      {
        lInternalList.add(networkToInternal(lNetworkList.get(lii)));
      }
      lInternal = new SymbolList(lInternalList);
    }

    return lInternal;
  }

  public File getGameDir()
  {
    return mGameDir;
  }

  /**
   * Attempt to identify this game from saved data - despite GDL scrambling.
   * If found, return a mapping from the saved GDL terms to the current GDL
   * terms.
   *
   * @param xiRulesheet - the GDL rulesheet.
   *
   * @return the mapping from saved -> current GDL terms, or an empty map if
   * no matching game could be found.
   */
  private Map<SymbolAtom, SymbolAtom> findMapping(SymbolList xiRulesheet)
  {
    Map<SymbolAtom, SymbolAtom> lMapping = null;

    // Get a flat representation of the GDL for this match.
    final List<SymbolAtom> lFlatGDL = flattenGDL(xiRulesheet);

    try
    {
      // Iterate over all the games we know, checking if any of them are equivalent to the current game.
      final File lGameDirs[] = LEARNING_DIR.listFiles();

      for (final File lGameDir : lGameDirs)
      {
        try
        {
          // Get a saved -> current GDL mapping.
          lMapping = getGDLMapping(lGameDir, lFlatGDL);
          if (lMapping != null)
          {
            // Found one - great.
            mGameDir = lGameDir;
            break;
          }
        }
        catch (final Exception lEx)
        {
          LOGGER.warn("Failed to consider a game - moving on");
          lMapping = null;
          lEx.printStackTrace();
        }
      }
    }
    catch (final Exception lEx)
    {
      LOGGER.warn("Failed to get a listing of games");
      lMapping = null;
      lEx.printStackTrace();
    }

    // If we didn't get a mapping, save the GDL so we'll be able to find one next time.
    if (lMapping == null)
    {
      saveGDL(lFlatGDL);
      lMapping = new HashMap<>();
    }

    return lMapping;
  }

  /**
   * @return a flattened version of the GDL.
   * @param xiRulesheet - the received rulesheet.
   */
  private List<SymbolAtom> flattenGDL(SymbolList xiRulesheet)
  {
    // Get a flat representation of the GDL.
    final List<SymbolAtom> lFlatGDL = new LinkedList<>();
    createAtomList(xiRulesheet, lFlatGDL);
    return lFlatGDL;
  }

  /**
   * Recursively convert a Symbol to a flattened string representation.
   *
   * @param xiSymbol   - the symbol (potentially nested) to convert
   * @param xbAtomList - a list of strings to append to
   */
  private void createAtomList(Symbol xiSymbol, List<SymbolAtom> xbAtomList)
  {
    if (xiSymbol instanceof SymbolList)
    {
      final SymbolList lSymbolList = (SymbolList)xiSymbol;
      for (int lii = 0; lii < lSymbolList.size(); lii++)
      {
        createAtomList(lSymbolList.get(lii), xbAtomList);
      }
    }
    else
    {
      xbAtomList.add((SymbolAtom)xiSymbol);
    }
  }

  /**
   * Assume that the specified game directory is for a matching game and return
   * the mapping from saved -> current GDL terms.  Returns null if this isn't
   * actually a matching game.
   *
   * @return the mapping, or null if there is no mapping.
   */
  private static Map<SymbolAtom, SymbolAtom> getGDLMapping(
                                                    File xiGameDir,
                                                    List<SymbolAtom> xiFlatGDL)
  {
    final String lGameName = xiGameDir.getName();

    // Load the GDL from disk.
    final String lGDL = readStringFromFile(new File(xiGameDir, GDL_FILENAME));
    if (lGDL == null)
    {
      return null;
    }
    final String[] lStoredAtoms = lGDL.split(" ");

    // Produce a mapping from the on-disk -> current version of the GDL.
    final Map<SymbolAtom, SymbolAtom> lMapping = new HashMap<>();

    if (xiFlatGDL.size() != lStoredAtoms.length)
    {
      LOGGER.trace("Not " + lGameName + ": GDL has wrong number of terms");
      return null;
    }

    if (lStoredAtoms.length > MAX_GDL_SIZE)
    {
      LOGGER.warn("Not attempting to check massive game with " + lStoredAtoms.length + " atoms");
      return null;
    }

    for (int lii = 0; lii < lStoredAtoms.length; lii++)
    {
      final SymbolAtom lGDLAtom = xiFlatGDL.get(lii);
      final SymbolAtom lStoredAtom = SymbolPool.getAtom(lStoredAtoms[lii]);

      // Don't map numerics - this is what caused us to recognize Amazons as being the same as Amazons Suicide!
      if (StringUtils.isNumeric(lGDLAtom.toString()) && !lGDLAtom.equals(lStoredAtom))
      {
        LOGGER.trace("Not " + lGameName + ":  Numeric constants differ");
        return null;
      }
      if (lMapping.containsKey(lStoredAtom))
      {
        if (!lMapping.get(lStoredAtom).equals(lGDLAtom))
        {
          LOGGER.trace("Not " + lGameName + ":  Already had " + lStoredAtom + " -> " + lMapping.get(lStoredAtom) + " but now have " + lStoredAtom + " -> " + lGDLAtom);
          return null;
        }
      }
      else
      {
        lMapping.put(lStoredAtom, lGDLAtom);
      }
    }

    LOGGER.info("Looks like a game of " + lGameName);

    return lMapping;
  }

  /**
   * Save the GDL for a game to disk.
   *
   * @param xiFlatGDL - flattened representation of the GDL.
   */
  private void saveGDL(List<SymbolAtom> xiFlatGDL)
  {
    // Create a directory for this game.
    final File lDir = new File(LEARNING_DIR, "" + System.currentTimeMillis());
    lDir.mkdirs();

    LOGGER.warn("Unrecognised game.  Created new game directory: " + lDir.getAbsolutePath());

    if (xiFlatGDL.size() > MAX_GDL_SIZE)
    {
      LOGGER.warn("Not saving GDL because it's too big (" + xiFlatGDL.size() + " atoms)");
    }

    // Convert the GDL to a string.
    final StringBuffer lGDLBuffer = new StringBuffer();
    for (final Symbol lSymbol : xiFlatGDL)
    {
      lGDLBuffer.append(lSymbol);
      lGDLBuffer.append(" ");
    }

    // Save the GDL to disk.
    writeStringToFile(lGDLBuffer.toString(), new File(lDir, GDL_FILENAME));

    mGameDir = lDir;
  }

  /**
   * Utility method to write a string to a file.
   *
   * @param xiString - the string.
   * @param xiFile - the file.
   */
  private static void writeStringToFile(String xiString, File xiFile)
  {
    try (final PrintWriter lWriter = new PrintWriter(xiFile))
    {
      lWriter.println(xiString);
    }
    catch (final IOException lEx)
    {
      System.err.println("Failed to write to file: " + lEx);
    }
  }

  /**
   * Utility method to read the contents of a file into a string.
   *
   * @param xiFile - the file.
   * @return the file's contents.
   */
  private static String readStringFromFile(File xiFile)
  {
    String lResult = null;
    try (final BufferedReader lReader = new BufferedReader(new FileReader(xiFile)))
    {
      lResult = lReader.readLine();
    }
    catch (final IOException lEx)
    {
      // Never mind
    }

    return lResult;
  }
}
