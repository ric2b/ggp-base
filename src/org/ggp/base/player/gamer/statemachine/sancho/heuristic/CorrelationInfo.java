
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

public class CorrelationInfo
{
  ScoreInfo valueInfo          = new ScoreInfo();
  ScoreInfo referenceValueInfo = new ScoreInfo();
  double    productSum         = 0;

  public void accrueSample(double value, double referenceValue)
  {
    productSum += value * referenceValue;
    valueInfo.accrueSample(value);
    referenceValueInfo.accrueSample(referenceValue);
  }

  public double getCorrelation()
  {
    double stdDev1 = valueInfo.getStdDev();
    double stdDev2 = referenceValueInfo.getStdDev();

    if (stdDev1 == 0 || stdDev2 == 0)
    {
      return 0;
    }
    return (productSum - valueInfo.numSamples * valueInfo.averageScore *
                         referenceValueInfo.averageScore) /
           ((valueInfo.numSamples - 1) * stdDev1 * stdDev2);
  }
}
