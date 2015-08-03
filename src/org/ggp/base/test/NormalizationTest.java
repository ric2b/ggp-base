package org.ggp.base.test;

import static org.junit.Assert.fail;

import org.junit.Test;

public class NormalizationTest
{
  private static final double EPSILON = 0.001;
  private static final int MIN_VISITS = 20;

  private static class ChildStats
  {
    int     mNumVisits;
    double  mScore;
    String  mName;
    double  mWeight;

    ChildStats(int visits, double score, String name)
    {
      mNumVisits = visits;
      mScore = score;
      mName = name;
    }
  }

  private static ChildStats[] Children =
  {
   new ChildStats(502,61.71,"f 1"),
   new ChildStats(502,50.13,"c 7"),
   new ChildStats(504,63.34,"d 9"),
   new ChildStats(503,55.83,"f 8"),
   new ChildStats(502,68.02,"f 3"),
   new ChildStats(502,61.80,"h 2"),
   new ChildStats(502,61.53,"d 5"),
   new ChildStats(502,62.65,"b 3"),
   new ChildStats(503,61.52,"b 1"),
   new ChildStats(502,67.49,"i 5"),
   new ChildStats(504,61.74,"i 1"),
   new ChildStats(504,57.91,"e 3"),
   new ChildStats(502,70.64,"a 3"),
   new ChildStats(11,90.9,"b 2"),
   new ChildStats(502,62.12,"a 6"),
   new ChildStats(502,67.25,"e 9"),
   new ChildStats(503,64.99,"c 6"),
   new ChildStats(603,64.21,"f 5"),
   new ChildStats(502,63.21,"e 4"),
   new ChildStats(503,67.88,"c 1"),
   new ChildStats(502,66.88,"g 8"),
   new ChildStats(502,60.16,"a 4"),
   new ChildStats(503,64.55,"i 8"),
   new ChildStats(502,64.63,"i 2"),
   new ChildStats(504,51.15,"g 3"),
   new ChildStats(503,69.06,"i 9"),
   new ChildStats(503,52.71,"c 2"),
   new ChildStats(503,63.35,"g 4"),
   new ChildStats(503,58.66,"f 2"),
   new ChildStats(504,66.62,"c 5"),
   new ChildStats(100,53.75,"h 9"),
   new ChildStats(503,66.82,"g 7"),
   new ChildStats(502,66.02,"i 6"),
   new ChildStats(506,73.01,"h 8"),
   new ChildStats(502,53.76,"h 4"),
   new ChildStats(502,66.15,"f 7"),
   new ChildStats(505,62.01,"d 2"),
   new ChildStats(503,57.95,"d 4"),
   new ChildStats(504,58.59,"i 7"),
   new ChildStats(504,61.54,"b 4"),
   new ChildStats(504,63.32,"f 4"),
   new ChildStats(504,65.56,"h 3"),
   new ChildStats(503,63.94,"d 1"),
   new ChildStats(502,63.86,"i 4"),
   new ChildStats(45,60.04,"c 4"),
   new ChildStats(502,62.38,"b 8"),
   new ChildStats(502,64.38,"e 6"),
   new ChildStats(503,68.87,"i 3"),
   new ChildStats(28,64.37,"a 2"),
   new ChildStats(503,63.88,"c 3"),
   new ChildStats(503,67.38,"g 6"),
   new ChildStats(502,54.00,"h 6"),
   new ChildStats(502,57.28,"f 6"),
   new ChildStats(503,64.27,"b 9"),
   new ChildStats(502,67.42,"f 9"),
   new ChildStats(257,45.28,"a 8"),
   new ChildStats(504,67.02,"h 5"),
   new ChildStats(502,67.50,"e 8"),
   new ChildStats(505,63.22,"d 3"),
   new ChildStats(502,55.57,"e 5"),
   new ChildStats(503,63.11,"e 2"),
   new ChildStats(64,56.35,"e 1"),
   new ChildStats(502,63.86,"a 9"),
   new ChildStats(503,63.97,"h 7"),
   new ChildStats(503,62.40,"a 1"),
   new ChildStats(502,72.68,"g 1"),
   new ChildStats(502,57.52,"d 8"),
   new ChildStats(502,68.83,"a 5"),
   new ChildStats(502,54.60,"g 2"),
   new ChildStats(502,63.63,"b 5"),
   new ChildStats(503,56.71,"a 7"),
   new ChildStats(504,70.74,"g 9"),
   new ChildStats(502,52.19,"c 8"),
   new ChildStats(502,57.00,"g 5"),
  };

  private void normalizeScores(int numParentVisits)
  {
    double weightTotal = 0;

    double pivotScore = -Double.MAX_VALUE;
    double pivotScoreWeight = -1;
    int highestScoreIndex = -1;

    for (int lii = 0; lii < Children.length; lii++)
    {
      ChildStats lChoice = Children[lii];

      double score = lChoice.mScore/100;
      int weight = lChoice.mNumVisits;
      // In principal it shouldn't matter which node we pick as the pivot node, but
      // choosing the most visited makes sense as it should have the lowest uncertainty
      if (score > pivotScore && weight > MIN_VISITS)
      {
        pivotScore = score;
        pivotScoreWeight = weight;
        highestScoreIndex = lii;
      }
    }

    System.out.println("Pivot is " + Children[highestScoreIndex].mName + " with a score of " + Children[highestScoreIndex].mScore + " and visit count of " + Children[highestScoreIndex].mNumVisits);
    double highestVisitFactor = Math.log(numParentVisits)/pivotScoreWeight;
    //  Note - the following line should remove biases from hyper-edge selection, but empirically
    //  (with or without this adjustment) normalization and hyper-edges just do not seem to mix well
    //  I do not know why, but for now normalization is just disabled in games with hyper-expansion
    double expBias = 0.5;
    double c = pivotScore + expBias*Math.sqrt(highestVisitFactor);
    double result = 0;

    for (int lii = 0; lii < Children.length; lii++)
    {
      ChildStats lChoice = Children[lii];

      //  Normalize by assuming that the highest scoring child has a 'correct' visit count and
      //  producing normalized visit counts for the other children based on their scores and the
      //  standard UCT distribution given the parent visit count.  Note that if the score
      //  distribution of the children is static (does not change with increasing samples) then
      //  this will precisely reproduce the visit count of continued UCT sampling.  If the distributions
      //  are changing (as is typically the case) then this will re-calculate the parent score estimates
      //  as if the current child scores WERE from a static distribution.  For example, suppose one
      //  child starts out looking good, but eventually converges to a lower score.  In such a case
      //  un-normalized MCTS will weight that child's score more highly than the renormalized version,
      //  and result in slower parent convergence.  Normalization should increase convergence rates in
      //  such cases, especially if child convergence is non-monotonic
      double chooserScore = lChoice.mScore/100;
      double weight = (lChoice.mNumVisits >= MIN_VISITS ? expBias*expBias*Math.log(numParentVisits)/((c-chooserScore)*(c-chooserScore)) : lChoice.mNumVisits);

      lChoice.mWeight = weight;

      assert(lii != highestScoreIndex || Math.abs(weight-lChoice.mNumVisits) < EPSILON);

      double score = chooserScore;

      result += weight*score;

      weightTotal += weight;
    }

    result /= weightTotal;

    for(int i = 0; i < Children.length; i++)
    {
      System.out.println("Move " + Children[i].mName + " score " + Children[i].mScore + ": old weight " + Children[i].mNumVisits + ", new weight " + Children[i].mWeight);
    }
    System.out.println("Normalized parent score: " + result);
    assert(result > 0);
  }

  @Test
  public void test()
  {
    normalizeScores(35297);
    fail("Not yet implemented");
  }

}
