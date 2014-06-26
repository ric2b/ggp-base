package org.ggp.base.player.gamer.statemachine.sancho;

class StateInfo
{
  public boolean isTerminal;
  public final double[] terminalScore;
  public boolean autoExpand;
  public static StateInfo bufferInstance = null;

  private StateInfo(int numRoles)
  {
    terminalScore = new double[numRoles];
  }

  public static void createBuffer(int numRoles)
  {
    bufferInstance = new StateInfo(numRoles);
  }
}