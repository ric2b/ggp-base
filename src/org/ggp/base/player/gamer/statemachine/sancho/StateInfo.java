package org.ggp.base.player.gamer.statemachine.sancho;

class StateInfo
{
  public static StateInfo bufferInstance = null;

  public boolean isTerminal;
  public final double[] terminalScore;
  public boolean autoExpand;

  private StateInfo(int numRoles)
  {
    terminalScore = new double[numRoles];
  }

  public static void createBuffer(int numRoles)
  {
    bufferInstance = new StateInfo(numRoles);
  }

  public static void destroyBuffer()
  {
    bufferInstance = null;
  }
}