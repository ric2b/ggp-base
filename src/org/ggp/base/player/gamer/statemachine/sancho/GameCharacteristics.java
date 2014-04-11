package org.ggp.base.player.gamer.statemachine.sancho;

public class GameCharacteristics
{
  boolean                                              isSimultaneousMove             = false;
  boolean                                              isPuzzle                       = false;
  boolean                                              isMultiPlayer                  = false;
  boolean                                              isIteratedGame                 = false;
  boolean                                              isPseudoSimultaneousMove       = false;
  boolean                                              moveChoicesFromMultipleFactors = false;

  public GameCharacteristics(int numRoles)
  {
    isPuzzle = (numRoles == 1);
    isMultiPlayer = (numRoles > 2);
  }

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
