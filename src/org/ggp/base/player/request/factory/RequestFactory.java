
package org.ggp.base.player.request.factory;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.Gamer;
import org.ggp.base.player.gamer.event.GamerUnrecognizedMatchEvent;
import org.ggp.base.player.request.factory.exceptions.RequestFormatException;
import org.ggp.base.player.request.grammar.AbortRequest;
import org.ggp.base.player.request.grammar.ErrorPseudoRequest;
import org.ggp.base.player.request.grammar.InfoRequest;
import org.ggp.base.player.request.grammar.PlayRequest;
import org.ggp.base.player.request.grammar.PreviewRequest;
import org.ggp.base.player.request.grammar.Request;
import org.ggp.base.player.request.grammar.StartRequest;
import org.ggp.base.player.request.grammar.StopRequest;
import org.ggp.base.util.game.GDLTranslator;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.gdl.factory.GdlFactory;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.symbol.factory.SymbolFactory;
import org.ggp.base.util.symbol.grammar.Symbol;
import org.ggp.base.util.symbol.grammar.SymbolAtom;
import org.ggp.base.util.symbol.grammar.SymbolList;

public final class RequestFactory
{
  private static final Logger LOGGER = LogManager.getLogger();

  public Request create(Gamer gamer, String source)
      throws RequestFormatException
  {
    try
    {
      SymbolList list = (SymbolList)SymbolFactory.create(source);
      SymbolAtom head = (SymbolAtom)list.get(0);

      String type = head.getValue().toLowerCase();
      if (type.equals("info"))
      {
        return createInfo(gamer, list);
      }
      else if (type.equals("preview"))
      {
        // !! ARR "preview" no longer used?  Can be removed?
        return createPreview(gamer, list);
      }
      else if (type.equals("start"))
      {
        if ( gamer.getMatch() != null )
        {
          //  Already playing a match - cannot construct a start request
          //  since the processing required to do so would disrupt the
          //  in-progress game (by corrupting the GDL translator), so instead
          //  construct a pseudo request that encapsulates the necessary error return
          return createErrorPseudoRequest(((SymbolAtom)list.get(1)).getValue(), "busy");
        }

        // This is the first we've seen of this match.  Set the match ID for logging.
        String lMatchID = ((SymbolAtom)list.get(1)).getValue();
        ThreadContext.put("matchID", lMatchID + "-" + gamer.getPort());

        if (gamer.getPort() == 9147)
        {
          LOGGER.info("======================================================");
          LOGGER.info("Beginning new game: " + lMatchID);
          LOGGER.info("Logs available at:  http://localhost:9199/localview/" + lMatchID);
        }

        // This is the first we've seen of the GDL.  Create a translator between the network and internal formats.
        gamer.setGDLTranslator(new GDLTranslator((SymbolList)list.get(3)));

        return createStart(gamer, list);
      }
      else if ( type.equals("play") || type.equals("stop") || type.equals("abort") )
      {
        if ( gamer.getMatch() == null )
        {
          String matchId = ((SymbolAtom)list.get(1)).getValue();
          gamer.notifyObservers(new GamerUnrecognizedMatchEvent(matchId));
          GamerLogger
              .logError("GamePlayer",
                        "Got " + type + " message not intended for current game: ignoring.");
          //  Not safe to attempt to construct a PlayRequest (etc.) in this case as there may
          //  be no GDL translator set
          return createErrorPseudoRequest(matchId, "busy");
        }

        if (type.equals("play"))
        {
          return createPlay(gamer, list);
        }
        else if (type.equals("stop"))
        {
          return createStop(gamer, list);
        }
        else if (type.equals("abort"))
        {
          return createAbort(gamer, list);
        }

        //  Keep the compiler quiet! (can't actually reach here)
        return null;
      }
      else
      {
        throw new IllegalArgumentException("Unrecognized request type!");
      }
    }
    catch (Exception e)
    {
      throw new RequestFormatException(source, e);
    }
  }

  private PlayRequest createPlay(Gamer gamer, SymbolList list)
  {
    if (list.size() != 3)
    {
      throw new IllegalArgumentException("Expected exactly 2 arguments!");
    }

    SymbolAtom arg1 = (SymbolAtom)list.get(1);
    Symbol arg2 = gamer.networkToInternal(list.get(2));

    String matchId = arg1.getValue();
    List<GdlTerm> moves = parseMoves(arg2);

    return new PlayRequest(gamer, matchId, moves);
  }

  private StartRequest createStart(Gamer gamer, SymbolList list)
  {
    if (list.size() < 6)
    {
      throw new IllegalArgumentException("Expected at least 5 arguments!");
    }

    SymbolAtom arg1 = (SymbolAtom)list.get(1);
    SymbolAtom arg2 = (SymbolAtom)(gamer.networkToInternal(list.get(2)));
    SymbolList arg3 = (SymbolList)(gamer.networkToInternal(list.get(3)));
    SymbolAtom arg4 = (SymbolAtom)list.get(4);
    SymbolAtom arg5 = (SymbolAtom)list.get(5);

    String matchId = arg1.getValue();
    GdlConstant roleName = (GdlConstant)GdlFactory.createTerm(arg2);
    String theRulesheet = arg3.toString();
    int startClock = Integer.valueOf(arg4.getValue());
    int playClock = Integer.valueOf(arg5.getValue());

    // For now, there are only five standard arguments. If there are any
    // new standard arguments added to START, they should be added here.

    Game theReceivedGame = Game.createEphemeralGame(theRulesheet);
    return new StartRequest(gamer,
                            matchId,
                            roleName,
                            theReceivedGame,
                            startClock,
                            playClock);
  }

  private ErrorPseudoRequest createErrorPseudoRequest(String matchId, String error)
  {
    return new ErrorPseudoRequest(matchId, error);
  }

  private StopRequest createStop(Gamer gamer, SymbolList list)
  {
    if (list.size() != 3)
    {
      throw new IllegalArgumentException("Expected exactly 2 arguments!");
    }

    SymbolAtom arg1 = (SymbolAtom)list.get(1);
    Symbol arg2 = gamer.networkToInternal(list.get(2));

    String matchId = arg1.getValue();
    List<GdlTerm> moves = parseMoves(arg2);

    return new StopRequest(gamer, matchId, moves);
  }

  private AbortRequest createAbort(Gamer gamer, SymbolList list)
  {
    if (list.size() != 2)
    {
      throw new IllegalArgumentException("Expected exactly 1 argument!");
    }

    SymbolAtom arg1 = (SymbolAtom)list.get(1);
    String matchId = arg1.getValue();

    return new AbortRequest(gamer, matchId);
  }

  private InfoRequest createInfo(Gamer gamer, SymbolList list)
  {
    if (list.size() != 1)
    {
      throw new IllegalArgumentException("Expected no arguments!");
    }

    return new InfoRequest(gamer);
  }

  private PreviewRequest createPreview(Gamer gamer, SymbolList list)
  {
    if (list.size() != 3)
    {
      throw new IllegalArgumentException("Expected exactly 2 arguments!");
    }

    SymbolAtom arg1 = (SymbolAtom)list.get(1);
    SymbolAtom arg2 = (SymbolAtom)list.get(2);

    String theRulesheet = arg1.toString();
    int previewClock = Integer.valueOf(arg2.getValue());

    Game theReceivedGame = Game.createEphemeralGame(theRulesheet);
    return new PreviewRequest(gamer, theReceivedGame, previewClock);
  }

  private List<GdlTerm> parseMoves(Symbol symbol)
  {
    if (symbol instanceof SymbolAtom)
    {
      return null;
    }
    List<GdlTerm> moves = new ArrayList<GdlTerm>();
    SymbolList list = (SymbolList)symbol;

    for (int i = 0; i < list.size(); i++)
    {
      moves.add(GdlFactory.createTerm(list.get(i)));
    }

    return moves;
  }
}