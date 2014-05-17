package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * @author steve
 *  Represents the fixed characteristics of a game, as derived either by static analysis
 *  or simulation during meta-gaming
 */
public class GameCharacteristics
{
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
      System.out.println("May is an iterated game");
    }

    if (isSimultaneousMove)
    {
      System.out.println("Game is a simultaneous turn game");
    }
    else if (isPseudoSimultaneousMove)
    {
      System.out.println("Game is pseudo-simultaneous (factorizable?)");
    }
    else
    {
      System.out.println("Game is not a simultaneous turn game");
    }

    if (isPuzzle)
    {
      System.out.println("Game is a 1-player puzzle");
    }
    else if (isMultiPlayer)
    {
      System.out.println("Game is a 3+-player game");
    }
    else
    {
      System.out.println("Is 2 player game");
    }
  }
}
