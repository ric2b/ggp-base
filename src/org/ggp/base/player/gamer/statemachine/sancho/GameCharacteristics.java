package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author steve
 *  Represents the fixed characteristics of a game, as derived either by static analysis
 *  or simulation during meta-gaming
 */
public class GameCharacteristics
{
  private static final Logger LOGGER = LogManager.getLogger();

  /**
   * Whether it is ever legal for multiple roles to have the same non-noop move
   * available in any given turn
   */
  boolean                                              isSimultaneousMove             = false;
  /**
   * Whether the game is a puzzle (same as single player)
   */
  boolean                                              isPuzzle                       = false;
  /**
   * Whether the game involves more than 2 players
   */
  boolean                                              isMultiPlayer                  = false;
  /**
   * Whether the game always offers the same move choices every turn
   */
  boolean                                              isIteratedGame                 = false;
  /**
   * Whether it is ever legal for multiple roles to have non-noop moves in the
   * same turn.  Note that this does not imply that this will be the case in
   * every turn necessarily
   */
  boolean                                              isPseudoSimultaneousMove       = false;
  /**
   * In a factorized game whether it is possible to have non-noop moves available
   * in more than one factor in any given move
   */
  boolean                                              moveChoicesFromMultipleFactors = false;

  /**
   * Constructor
   * @param numRoles Number of roles in the game
   */
  public GameCharacteristics(int numRoles)
  {
    isPuzzle = (numRoles == 1);
    isMultiPlayer = (numRoles > 2);
  }

  /**
   * Dump a description of the game characteristics to output logging
   */
  public void report()
  {
    if (isIteratedGame)
    {
      LOGGER.info("May be an iterated game");
    }

    if (isSimultaneousMove)
    {
      LOGGER.info("Game is a simultaneous turn game");
    }
    else if (isPseudoSimultaneousMove)
    {
      LOGGER.info("Game is pseudo-simultaneous (factorizable?)");
    }
    else
    {
      LOGGER.info("Game is not a simultaneous turn game");
    }

    if (isPuzzle)
    {
      LOGGER.info("Game is a 1-player puzzle");
    }
    else if (isMultiPlayer)
    {
      LOGGER.info("Game is a 3+-player game");
    }
    else
    {
      LOGGER.info("Is 2 player game");
    }
  }
}
