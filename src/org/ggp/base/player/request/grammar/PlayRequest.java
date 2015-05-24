
package org.ggp.base.player.request.grammar;

import java.util.List;

import org.ggp.base.player.event.PlayerTimeEvent;
import org.ggp.base.player.gamer.Gamer;
import org.ggp.base.player.gamer.event.GamerUnrecognizedMatchEvent;
import org.ggp.base.player.gamer.exception.MoveSelectionException;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.match.Match;
import org.ggp.base.util.symbol.factory.SymbolFactory;
import org.ggp.base.util.symbol.factory.exceptions.SymbolFormatException;

public final class PlayRequest extends Request
{
  private final Gamer         gamer;
  private final String        matchId;
  private final List<GdlTerm> moves;

  public PlayRequest(Gamer gamer, String matchId, List<GdlTerm> moves)
  {
    this.gamer = gamer;
    this.matchId = matchId;
    this.moves = moves;
  }

  @Override
  public String getMatchId()
  {
    return matchId;
  }

  @Override
  public String process(long receptionTime)
  {
    // First, check to ensure that this play request is for the match
    // we're currently playing. If we're not playing a match, or we're
    // playing a different match, send back "busy".
    Match lMatch = gamer.getMatch();
    if (lMatch == null || !lMatch.getMatchId().equals(matchId))
    {
      gamer.notifyObservers(new GamerUnrecognizedMatchEvent(matchId));
      GamerLogger.logError("GamePlayer", "Ignoring play message for " + matchId +
                           " because the current match is " + lMatch.getMatchId());
      return "busy";
    }

    if (moves != null)
    {
      lMatch.appendMoves(moves);
    }

    try
    {
      gamer.notifyObservers(new PlayerTimeEvent(lMatch.getPlayClock() * 1000));
      String lInternalMove = gamer.selectMove(lMatch.getPlayClock() * 1000 + receptionTime).toString();
      String lNetworkMove = gamer.internalToNetwork(SymbolFactory.create(lInternalMove)).toString();
      return lNetworkMove;
    }
    catch (SymbolFormatException|MoveSelectionException lEx)
    {
      GamerLogger.logStackTrace("GamePlayer", lEx);
      return "nil";
    }
  }

  @Override
  public String toString()
  {
    return "play";
  }
}