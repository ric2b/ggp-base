
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

public class HeuristicScoreInfo
{
  CorrelationInfo[] roleCorrelationInfos;
  CorrelationInfo[] roleWinLossCorrelationInfos;
  int               lastValue        = -1;
  boolean[]         hasRoleChanges;
  double            noChangeTurnRate = 0;
  double            totalValue       = 0;
  int               numSamples       = 0;

  public HeuristicScoreInfo(int numRoles)
  {
    roleCorrelationInfos = new CorrelationInfo[numRoles];
    roleWinLossCorrelationInfos = new CorrelationInfo[numRoles];
    hasRoleChanges = new boolean[numRoles];
    for (int i = 0; i < numRoles; i++)
    {
      roleCorrelationInfos[i] = new CorrelationInfo();
      roleWinLossCorrelationInfos[i] = new CorrelationInfo();
    }
  }

  public void accrueSample(double value, double[] roleValues)
  {
    for (int i = 0; i < roleCorrelationInfos.length; i++)
    {
      roleCorrelationInfos[i].accrueSample(value, roleValues[i]);

      if (roleValues[i] == 0 || roleValues[i] == 100)
      {
        roleWinLossCorrelationInfos[i].accrueSample(value, roleValues[i]);
      }
    }

    totalValue += value;
    numSamples++;
  }

  public double[] getRoleCorrelations()
  {
    double[] result = new double[roleCorrelationInfos.length];

    for (int i = 0; i < roleCorrelationInfos.length; i++)
    {
      result[i] = roleCorrelationInfos[i].getCorrelation();
    }

    return result;
  }

  public double[] getWinLossRoleCorrelations()
  {
    double[] result = new double[roleCorrelationInfos.length];

    for (int i = 0; i < roleCorrelationInfos.length; i++)
    {
      result[i] = roleWinLossCorrelationInfos[i].getCorrelation();
    }

    return result;
  }

  public double getAverageValue()
  {
    return totalValue / numSamples;
  }
}
