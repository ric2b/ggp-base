
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.util.stats.PearsonCorrelation;

public class HeuristicScoreInfo
{
  private PearsonCorrelation[] roleCorrelation;
  int                          lastValue        = -1;
  boolean[]                    hasRoleChanges;
  double                       noChangeTurnRate = 0;
  private double               totalValue       = 0;
  private int                  numSamples       = 0;

  public HeuristicScoreInfo(int numRoles)
  {
    roleCorrelation = new PearsonCorrelation[numRoles];
    hasRoleChanges = new boolean[numRoles];
    for (int i = 0; i < numRoles; i++)
    {
      roleCorrelation[i] = new PearsonCorrelation();
    }
  }

  public void accrueSample(double value, double[] roleValues)
  {
    for (int i = 0; i < roleCorrelation.length; i++)
    {
      roleCorrelation[i].sample(value, roleValues[i]);
    }

    totalValue += value;
    numSamples++;
  }

  public double[] getRoleCorrelations()
  {
    double[] result = new double[roleCorrelation.length];

    for (int i = 0; i < roleCorrelation.length; i++)
    {
      result[i] = roleCorrelation[i].getCorrelation();
    }

    return result;
  }
}
