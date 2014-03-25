
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.stats.PearsonCorrelation;

public class HeuristicScoreInfo
{
  private PearsonCorrelation[] roleCorrelationInfos;
  private PearsonCorrelation[] roleWinLossCorrelationInfos;
  int                          lastValue        = -1;
  boolean[]                    hasRoleChanges;
  double                       noChangeTurnRate = 0;
  private double               totalValue       = 0;
  private int                  numSamples       = 0;

  public HeuristicScoreInfo(int numRoles)
  {
    roleCorrelationInfos = new PearsonCorrelation[numRoles];
    roleWinLossCorrelationInfos = new PearsonCorrelation[numRoles];
    hasRoleChanges = new boolean[numRoles];
    for (int i = 0; i < numRoles; i++)
    {
      roleCorrelationInfos[i] = new PearsonCorrelation();
      roleWinLossCorrelationInfos[i] = new PearsonCorrelation();
    }
  }

  public void accrueSample(double value, double[] roleValues)
  {
    for (int i = 0; i < roleCorrelationInfos.length; i++)
    {
      roleCorrelationInfos[i].sample(value, roleValues[i]);

      if (roleValues[i] == 0 || roleValues[i] == 100)
      {
        roleWinLossCorrelationInfos[i].sample(value, roleValues[i]);
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
