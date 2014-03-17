package org.ggp.base.player.gamer.statemachine.sample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.ggp.base.util.symbol.factory.SymbolFactory;
import org.ggp.base.util.symbol.factory.exceptions.SymbolFormatException;
import org.ggp.base.util.symbol.grammar.Symbol;
import org.ggp.base.util.symbol.grammar.SymbolList;

public class Unscrambler
{
  private static final String LEARNING_DIR = "data\\games";
  private static final String GDL_FILE = "gdl.txt";

  /**
   * Attempt to identify this game from saved data - despite GDL scrambling.
   * If found, return a mapping from the saved GDL terms to the current GDL
   * terms.
   *
   * @param xiRulesheet - the GDL rulesheet.
   *
   * @return the mapping from old -> new GDL terms, or null if no matching
   * game could be found.
   */
  public Map<String, String> findMapping(String xiRulesheet)
  {
    Map<String, String> lMapping = null;

    // Get a flat representation of the GDL for this match.
    final List<String> lFlatGDL = flattenGDL(xiRulesheet);

    try
    {
      // Iterate over all the games that we've previously played, checking
      // if any of them are equivalent to the current game.
      final File lLearningDir = new File(LEARNING_DIR);
      final File lGameDirs[] = lLearningDir.listFiles();

      for (final File lGameDir : lGameDirs)
      {
        try
        {
          // Get an old -> new GDL mapping.
          lMapping = getGDLMapping(lGameDir, lFlatGDL);
          if (lMapping != null)
          {
            // Found one - great.
            break;
          }
        }
        catch (final Exception lEx)
        {
          debug("Failed to consider a game - moving on");
          lMapping = null;
          lEx.printStackTrace();
        }
      }
    }
    catch (final Exception lEx)
    {
      debug("Failed to get a listing of games");
      lMapping = null;
      lEx.printStackTrace();
    }

    // If we didn't get a mapping, save the GDL so we'll be able to find one
    // next time.
    if (lMapping == null)
    {
      saveGDL(lFlatGDL);
    }

    return lMapping;
  }

  /**
   * @return a flattened string version of the GDL.
   * @param xiRulesheet - the received rulesheet.
   */
  private List<String> flattenGDL(String xiRulesheet)
  {
    // Get a flat representation of the GDL.
    final List<String> lFlatGDL = new LinkedList<>();
    try
    {
      final Symbol lTopLevel = SymbolFactory.create(xiRulesheet);
      createAtomList(lTopLevel, lFlatGDL);
    }
    catch (final SymbolFormatException lEx)
    {
      lEx.printStackTrace();
    }

    return lFlatGDL;
  }

  /**
   * Recursively convert a Symbol to a flattened string representation.
   *
   * @param xiSymbol   - the symbol (potentially nested) to convert
   * @param xbAtomList - a list of strings to append to
   */
  private void createAtomList(Symbol xiSymbol, List<String> xbAtomList)
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
      xbAtomList.add(xiSymbol.toString());
    }
  }

  /**
   * Assume that the specified game directory is for a matching game and return
   * the mapping from old -> current GDL terms.  Returns null if this isn't
   * actually a matching game.
   *
   * @return the mapping, or null if there is no mapping.
   */
  private static Map<String, String> getGDLMapping(File xiGameDir,
                                                   List<String> xiFlatGDL)
  {
    // Load the GDL from disk.
    final String lGDL = readStringFromFile(new File(xiGameDir, GDL_FILE));
    if (lGDL == null)
    {
      return null;
    }
    final String[] lStoredAtoms = lGDL.split(" ");

    final String lGameName = xiGameDir.getName();

    // Produce a mapping from the on-disk -> current version of the GDL.
    final Map<String, String> lMapping = new HashMap<>();

    int lii = 0;
    while ((!xiFlatGDL.isEmpty()) && (lii < lStoredAtoms.length))
    {
      final String lGDLAtom = xiFlatGDL.remove(0);
      final String lStoredAtom = lStoredAtoms[lii++]; // !! ARR [P3] Could be out-of-bounds
      if (lMapping.containsKey(lStoredAtom))
      {
        if (!lMapping.get(lStoredAtom).equals(lGDLAtom))
        {
          debug("Not " + lGameName + ":  Already had " + lStoredAtom + " -> " + lMapping.get(lStoredAtom) + " but now have " + lStoredAtom + " -> " + lGDLAtom);
          return null;
        }
      }
      else
      {
        lMapping.put(lStoredAtom, lGDLAtom);
      }
    }

    if (lii != lStoredAtoms.length)
    {
      System.err.println("Not " + lGameName + ": Didn't use all of the stored GDL");
      return null;
    }

    debug("Looks like a game of " + lGameName);

    return lMapping;
  }

  /**
   * Save the GDL for a game to disk.
   *
   * @param xiFlatGDL - flattened representation of the GDL.
   */
  private static void saveGDL(List<String> xiFlatGDL)
  {
    // Create a directory for this game.
    final String lDirName = LEARNING_DIR + "\\" + System.currentTimeMillis();
    final File lDir = new File(lDirName);
    lDir.mkdirs();

    // Convert the GDL to a string.
    final StringBuffer lGDLBuffer = new StringBuffer();
    for (final String lSymbol : xiFlatGDL)
    {
      lGDLBuffer.append(lSymbol);
      lGDLBuffer.append(" ");
    }

    // Save the GDL to disk.
    writeStringToFile(lGDLBuffer.toString(), new File(lDir, GDL_FILE));
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
    try (final BufferedReader lReader =
                                    new BufferedReader(new FileReader(xiFile)))
    {
      lResult = lReader.readLine();
    }
    catch (final IOException lEx)
    {
      // Never mind
    }

    return lResult;
  }

  /**
   * Print a debug message.
   *
   * @param xiStrings - objects to print (concatenated with no spaces).
   */
  private static void debug(Object... xiStrings)
  {
    System.out.print("" + System.currentTimeMillis() + ": ");
    for (final Object lObject : xiStrings)
    {
      System.out.print(lObject);
    }
    System.out.println("");
  }
}
