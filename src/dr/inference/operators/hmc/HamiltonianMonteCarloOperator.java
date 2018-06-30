/*
 * NewHamiltonianMonteCarloOperator.java
 *
 * Copyright (c) 2002-2017 Alexei Drummond, Andrew Rambaut and Marc Suchard
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.inference.operators.hmc;

import java.util.Arrays;

import cern.colt.matrix.DoubleFactory2D;
import cern.colt.matrix.DoubleMatrix1D;
import cern.colt.matrix.DoubleMatrix2D;
import cern.colt.matrix.impl.DenseDoubleMatrix2D;
import cern.colt.matrix.linalg.Algebra;
import dr.inference.hmc.GradientWrtParameterProvider;
import dr.inference.hmc.HessianWrtParameterProvider;
import dr.inference.model.Parameter;
import dr.inference.operators.AbstractCoercableOperator;
import dr.inference.operators.CoercionMode;
import dr.math.*;
import dr.math.distributions.MultivariateNormalDistribution;
import dr.math.matrixAlgebra.RobustEigenDecomposition;
import dr.math.matrixAlgebra.SymmetricMatrix;
import dr.util.Transform;

/**
 * @author Max Tolkoff
 * @author Marc A. Suchard
 */

public class HamiltonianMonteCarloOperator extends AbstractCoercableOperator {

    final GradientWrtParameterProvider gradientProvider;
    protected double stepSize;
    protected final int nSteps;
    private final double randomStepCountFraction;
    MultivariateNormalDistribution drawDistribution;
    final LeapFrogEngine leapFrogEngine;
    final MassProvider massProvider;

    HamiltonianMonteCarloOperator(CoercionMode mode, double weight, GradientWrtParameterProvider gradientProvider,
                                         Parameter parameter, Transform transform,
                                         double stepSize, int nSteps, double drawVariance,
                                         double randomStepCountFraction) {
        this(mode, weight, gradientProvider, parameter, transform,
                stepSize, nSteps, drawVariance, randomStepCountFraction, false);
    }

    public HamiltonianMonteCarloOperator(CoercionMode mode, double weight, GradientWrtParameterProvider gradientProvider,
                                         Parameter parameter, Transform transform,
                                         double stepSize, int nSteps, double drawVariance,
                                         double randomStepCountFraction,
                                         boolean preConditioning) {
        super(mode);
        setWeight(weight);
        setTargetAcceptanceProbability(0.8); // Stan default

        this.gradientProvider = gradientProvider;
        this.stepSize = stepSize;
        this.nSteps = nSteps;
        this.randomStepCountFraction = randomStepCountFraction;

        this.leapFrogEngine = (transform != null ?
                new LeapFrogEngine.WithTransform(parameter, transform, getDefaultInstabilityHandler()) :
                new LeapFrogEngine.Default(parameter, getDefaultInstabilityHandler()));

        this.massProvider = (!preConditioning ?
                new MassProvider.Default(gradientProvider.getDimension(), drawVariance) :
                (transform != null ?
                        new MassProvider.PreConditioningWithTransform(drawVariance, (HessianWrtParameterProvider) gradientProvider, transform) :
                        new MassProvider.PreConditioning(drawVariance, (HessianWrtParameterProvider) gradientProvider))
        );
        double[] mean = new double[gradientProvider.getDimension()];
        this.drawDistribution = new MultivariateNormalDistribution(mean, massProvider.getMass());

        this.preconditioning = setupPreconditioning();
    }

    @Override
    public String getPerformanceSuggestion() {
        return null;
    }

    @Override
    public String getOperatorName() {
        return "Vanilla HMC operator";
    }

    private MassPreconditioner preconditioning;
    private AbstractParticleOperator.Options runtimeOptions;

    private MassPreconditioner setupPreconditioning() {

        return new MassPreconditioner.NoPreconditioning(gradientProvider.getDimension());
    }

    private boolean shouldUpdatePreconditioning() {
        return runtimeOptions.preconditioningUpdateFrequency > 0
                && (getCount() % runtimeOptions.preconditioningUpdateFrequency == 0);
    }

    @Override
    public double doOperation() {

        if (shouldUpdatePreconditioning()) {
            preconditioning = setupPreconditioning();
        }

        try {
            return leapFrog();
        } catch (NumericInstabilityException e) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private long count = 0;

    private static final boolean DEBUG = false;

    static class NumericInstabilityException extends Exception { }

    private int getNumberOfSteps() {
        int count = nSteps;
        if (randomStepCountFraction > 0.0) {
            double draw = count * (1.0 + randomStepCountFraction * (MathUtils.nextDouble() - 0.5));
            count = Math.max(1, (int) draw);
        }
        return count;
    }

    static double getScaledDotProduct(final double[] momentum,
                                      final double[][] massInverse) {
        double total = 0.0;
        for (int i = 0; i < momentum.length; i++) {
            double sum = 0.0;
            for (int j = 0; j < momentum.length; j++) {
                sum += massInverse[i][j] * momentum[j];
            }
            total += momentum[i] * sum / 2.0;
        }
        return total;
    }

    static double[] drawInitialMomentum(final MultivariateNormalDistribution distribution) {
        return distribution.nextMultivariateNormal();
    }

    private void setPreconditioning(MassProvider massProvider){
        double[] mean = new double[gradientProvider.getDimension()];
        Arrays.fill(mean, 0.0);
        massProvider.updateMass();
        this.drawDistribution = new MultivariateNormalDistribution(mean, massProvider.getMassInverse());
    }

    private double leapFrog() throws NumericInstabilityException {

        if (DEBUG) {
            if (count % 5 == 0) {
                System.err.println("HMC step size: " + stepSize);
            }
            ++count;
        }

        setPreconditioning(massProvider);

        final double[] position = leapFrogEngine.getInitialPosition();
        final double[] momentum = drawInitialMomentum(drawDistribution);

        final double prop = getScaledDotProduct(momentum, massProvider.getMassInverse()) +
                leapFrogEngine.getParameterLogJacobian();

        leapFrogEngine.updateMomentum(position, momentum,
                gradientProvider.getGradientLogDensity(), stepSize / 2);

        int nStepsThisLeap = getNumberOfSteps();

        for (int i = 0; i < nStepsThisLeap; i++) { // Leap-frog

            leapFrogEngine.updatePosition(position, momentum, massProvider.getMassInverse(), stepSize);

            if (i < (nStepsThisLeap - 1)) {
                leapFrogEngine.updateMomentum(position, momentum,
                        gradientProvider.getGradientLogDensity(), stepSize);
            }
        }

        leapFrogEngine.updateMomentum(position, momentum,
                gradientProvider.getGradientLogDensity(), stepSize / 2);

        final double res = getScaledDotProduct(momentum, massProvider.getMassInverse()) +
                leapFrogEngine.getParameterLogJacobian();

        return prop - res; //hasting ratio
    }

    @Override
    public double getCoercableParameter() {
        return Math.log(stepSize);
    }

    @Override
    public void setCoercableParameter(double value) {
        stepSize = Math.exp(value);
    }

    @Override
    public double getRawParameter() {
        return stepSize;
    }

    enum InstabilityHandler {

        REJECT {
            @Override
            void checkValue(double x) throws NumericInstabilityException {
                if (Double.isNaN(x)) throw new NumericInstabilityException();
            }
        },

        DEBUG {
            @Override
            void checkValue(double x) throws NumericInstabilityException {
                if (Double.isNaN(x)) {
                    System.err.println("Numerical instability in HMC momentum; throwing exception");
                    throw new NumericInstabilityException();
                }
            }
        },

        IGNORE {
            @Override
            void checkValue(double x) {
                // Do nothing
            }
        };

        abstract void checkValue(double x) throws NumericInstabilityException;
    }

    protected InstabilityHandler getDefaultInstabilityHandler() {
        if (DEBUG) {
            return InstabilityHandler.DEBUG;
        } else {
            return InstabilityHandler.REJECT;
        }
    }

    interface LeapFrogEngine {

        double[] getInitialPosition();

        double getParameterLogJacobian();

        void updateMomentum(final double[] position,
                            final double[] momentum,
                            final double[] gradient,
                            final double functionalStepSize) throws NumericInstabilityException;

        void updatePosition(final double[] position,
                            final double[] momentum,
                            final double[][] massInverse,
                            final double functionalStepSize);

        void setParameter(double[] position);

        class Default implements LeapFrogEngine {

            final protected Parameter parameter;
            final private InstabilityHandler instabilityHandler;

            protected Default(Parameter parameter, InstabilityHandler instabilityHandler) {
                this.parameter = parameter;
                this.instabilityHandler = instabilityHandler;
            }

            @Override
            public double[] getInitialPosition() {
                return parameter.getParameterValues();
            }

            @Override
            public double getParameterLogJacobian() {
                return 0;
            }

            @Override
            public void updateMomentum(double[] position, double[] momentum, double[] gradient,
                                       double functionalStepSize) throws NumericInstabilityException {

                final int dim = momentum.length;
                for (int i = 0; i < dim; ++i) {
                    momentum[i] += functionalStepSize  * gradient[i];
                    instabilityHandler.checkValue(momentum[i]);
                }
            }

            @Override
            public void updatePosition(double[] position, double[] momentum, double[][] massInverse,
                                       double functionalStepSize) {

                final int dim = momentum.length;
                for (int i = 0; i < dim; i++) {
                    double sum = 0.0;
                    for (int j = 0; j < dim; j++) {
                        sum += massInverse[i][j] * momentum[j];
                    }
                    position[i] += functionalStepSize * sum;
                }
                setParameter(position);
            }

            public void setParameter(double[] position) {

                final int dim = position.length;
                for (int j = 0; j < dim; ++j) {
                    parameter.setParameterValueQuietly(j, position[j]);
                }
                parameter.fireParameterChangedEvent();  // Does not seem to work with MaskedParameter
            }
        }

        class WithTransform extends Default {

            final private Transform transform;
            double[] unTransformedPosition;

            private WithTransform(Parameter parameter, Transform transform, InstabilityHandler instabilityHandler) {
                super(parameter, instabilityHandler);
                this.transform = transform;
            }

            @Override
            public double getParameterLogJacobian() {
                return transform.getLogJacobian(unTransformedPosition,0, unTransformedPosition.length);
            }

            @Override
            public double[] getInitialPosition() {
                unTransformedPosition = super.getInitialPosition();
                return transform.transform(unTransformedPosition, 0, unTransformedPosition.length);
            }

            @Override
            public void updateMomentum(double[] position, double[] momentum, double[] gradient,
                                       double functionalStepSize) throws NumericInstabilityException {

                gradient = transform.updateGradientLogDensity(gradient, unTransformedPosition,
                        0, unTransformedPosition.length);

                super.updateMomentum(position, momentum, gradient, functionalStepSize);
            }

            @Override
            public void setParameter(double[] position) {
                unTransformedPosition = transform.inverse(position, 0, position.length);
                super.setParameter(unTransformedPosition);
            }
        }
    }

    protected interface MassProvider {

        double[][] getMass();

        double[][] getMassInverse();

        void updateMass();

        class Default implements MassProvider {
            final double drawVariance;
            final int dim;
            final double[][] mass;
            final double[][] massInverse;
            MultivariateNormalDistribution drawDistribution;

            Default(int dim, double drawVariance) {
                this.dim = dim;
                this.drawVariance = drawVariance;
                this.drawDistribution = setDrawDistribution(drawVariance);

                this.mass = new double[dim][dim];
                this.massInverse = new double[dim][dim];
                for (int i = 0; i < dim; i++) {
                    Arrays.fill(mass[i], 0.0);
                    Arrays.fill(massInverse[i], 0.0);
                    massInverse[i][i] = drawVariance;
                    mass[i][i] = 1.0 / massInverse[i][i];
                }
            }

            private MultivariateNormalDistribution setDrawDistribution(double drawVariance) {
                double[] mean = new double[dim];
                Arrays.fill(mean, 0.0);
                return new MultivariateNormalDistribution(mean, 1.0/drawVariance);
            }

            public double[][] getMass() {
                return mass;
            }

            @Override
            public double[][] getMassInverse() {
                return massInverse;
            }

            @Override
            public void updateMass() {
                // Do nothing;
            }
        }

        class PreConditioning extends Default {

            final HessianWrtParameterProvider hessianWrtParameterProvider;

            PreConditioning(double drawVariance, HessianWrtParameterProvider hessianWrtParameterProvider) {
                super(hessianWrtParameterProvider.getDimension(), drawVariance);
                if (!(hessianWrtParameterProvider instanceof HessianWrtParameterProvider)) {
                    throw new IllegalArgumentException("Must provide a HessianProvider for preConditioning.");
                }
                this.hessianWrtParameterProvider = hessianWrtParameterProvider;
//                setMassMatrices(hessianWrtParameterProvider.getDiagonalHessianLogDensity());
            }

            public void setMassMatrices(double[] diagonalHessian) {
                for (int i = 0; i < dim; i++) {
                    Arrays.fill(massInverse[i], 0.0);
                }
                double[] boundedMassInverse = boundMassInverse(diagonalHessian);
                for (int i = 0; i < dim; i++) {
                    massInverse[i][i] = boundedMassInverse[i];
                    mass[i][i] = 1.0 / massInverse[i][i];
                }
            }

            @Override
            public void updateMass() {
                setMassMatrices(hessianWrtParameterProvider.getDiagonalHessianLogDensity());
            }

            private double[] boundMassInverse(double[] diagonalHessian) {

                double sum = 0.0;
                final double lowerBound = 1E-2;
                final double upperBound = 1E2;
                double[] boundedMassInverse = new double[dim];

                for (int i = 0; i < dim; i++) {
                    boundedMassInverse[i] = -1.0 / diagonalHessian[i];
                    if (boundedMassInverse[i] < lowerBound) {
                        boundedMassInverse[i] = lowerBound;
                    } else if (boundedMassInverse[i] > upperBound) {
                        boundedMassInverse[i] = upperBound;
                    }
                    sum += 1.0 / boundedMassInverse[i];
                }
                final double mean = sum / dim;
                for (int i = 0; i < dim; i++) {
                    boundedMassInverse[i] = boundedMassInverse[i] * mean;
                }
                return boundedMassInverse;
            }

        }

        class PreConditioningWithTransform extends PreConditioning {

            final Transform transform;

            PreConditioningWithTransform(double drawVariance, HessianWrtParameterProvider hessianWrtParameterProvider,
                                         Transform transform) {
                super(drawVariance, hessianWrtParameterProvider);
                this.transform = transform;
            }

            private void setArbitraryMatrix(double[][] matrix) {
                double[] diagonal = drawDistribution.nextMultivariateNormal();
                double multiplier = 1.2;
                for (int i = 0; i < dim; i++) {
                    matrix[i][i] = Math.abs(diagonal[i]) * multiplier;
                }
            }

            public void setMassMatrices(double[] diagonalHessian) {
//                double[] gradient = hessianWrtParameterProvider.getGradientLogDensity();
//                double[] unTransformedPosition = hessianWrtParameterProvider.getParameter().getParameterValues();
//                diagonalHessian = transform.updateDiagonalHessianLogDensity(diagonalHessian, gradient, unTransformedPosition,
//                        0, diagonalHessian.length);
//
//                double[] testHessian = NumericalDerivative.diagonalHessian(numeric1, transform.transform(hessianWrtParameterProvider.getParameter().getParameterValues(), 0, dim));
                SymmetricMatrix hessian = getNumericalHessianCentral();
                double[][] hessianInverse = hessian.inverse().toComponents();


//                super.setMassMatrices(diagonalHessian);
//                setArbitraryMatrix(massMatrixInverse);
                for (int i = 0; i < dim; i++) {
                    for (int j = 0; j < dim; j++) {
                        massInverse[i][j] = -hessianInverse[i][j];
                        mass[i][j] = -hessian.component(i, j);
                    }
                }
            }

            private static final double MIN_EIGENVALUE = -0.5; // TODO Bad magic number

            private void boundEigenvalues(DoubleMatrix1D eigenvalues) {

                for (int i = 0; i < eigenvalues.cardinality(); ++i) {
                    if (eigenvalues.get(i) > MIN_EIGENVALUE) {
                        eigenvalues.set(i, MIN_EIGENVALUE);
                    }
                }
            }

            private void scaleEigenvalues(DoubleMatrix1D eigenvalues) {
                double sum = 0.0;
                for (int i = 0; i < eigenvalues.cardinality(); ++i) {
                    sum += eigenvalues.get(i);
                }

                double mean = -sum / eigenvalues.cardinality();

                for (int i = 0; i < eigenvalues.cardinality(); ++i) {
                    eigenvalues.set(i, eigenvalues.get(i) / mean);
                }
            }

            private void normalizeEigenvalues(DoubleMatrix1D eigenvalues) {
                boundEigenvalues(eigenvalues);
                scaleEigenvalues(eigenvalues);
            }

            private SymmetricMatrix getNumericalHessianCentral() {
                double[][] hessian = new double[dim][dim];
                double[] oldUntransformedPosition = hessianWrtParameterProvider.getParameter().getParameterValues();
                double[] oldTransformedPosition = transform.transform(oldUntransformedPosition, 0, dim);
                double[][] gradientPlus = new double[dim][dim];
                double[][] gradientMinus = new double[dim][dim];
                double[] h = new double[dim];
                for (int i = 0; i < dim; i++) {
                    h[i] = MachineAccuracy.SQRT_SQRT_EPSILON*(Math.abs(oldTransformedPosition[i]) + 1.0);
                    hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(oldTransformedPosition[i] + h[i]));
                    double[] tempGradient = hessianWrtParameterProvider.getGradientLogDensity();
                    gradientPlus[i] = transform.updateGradientLogDensity(tempGradient, hessianWrtParameterProvider.getParameter().getParameterValues(),
                            0, dim);

                    hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(oldTransformedPosition[i] - h[i]));
                    tempGradient = hessianWrtParameterProvider.getGradientLogDensity();
                    gradientMinus[i] = transform.updateGradientLogDensity(tempGradient, hessianWrtParameterProvider.getParameter().getParameterValues(),
                            0, dim);
                    hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(oldTransformedPosition[i]));
                }
                for (int i = 0; i < dim; i++) {
                    for (int j = i; j < dim; j++) {
                        hessian[j][i] = hessian[i][j] = (gradientPlus[i][j] - gradientMinus[i][j]) / (4.0 * h[j]) + (gradientPlus[j][i] - gradientMinus[j][i]) / (4.0 * h[i]);
                    }
                }
//                double[] gradient = hessianWrtParameterProvider.getGradientLogDensity();
//                double[] unTransformedPosition = hessianWrtParameterProvider.getParameter().getParameterValues();
//                double[] diagonalHessian = hessianWrtParameterProvider.getDiagonalHessianLogDensity();
//                diagonalHessian = transform.updateDiagonalHessianLogDensity(diagonalHessian, gradient, unTransformedPosition,
//                        0, diagonalHessian.length);
//                double[][] hessianCheck = getNumericalHessianCheck(numeric1,
//                        transform.transform(hessianWrtParameterProvider.getParameter().getParameterValues(), 0, dim));
//                double[][] hessianCheck2 = getNumericalHessianCheckForward(dim,
//                        transform.transform(hessianWrtParameterProvider.getParameter().getParameterValues(), 0, dim));
                Algebra algebra = new Algebra();

                DoubleMatrix2D H = new DenseDoubleMatrix2D(hessian);
                RobustEigenDecomposition decomposition = new RobustEigenDecomposition(H);
                DoubleMatrix1D eigenvalues = decomposition.getRealEigenvalues();

                normalizeEigenvalues(eigenvalues);

                DoubleMatrix2D V = decomposition.getV();
                DoubleMatrix2D newHessian = algebra.mult(
                        algebra.mult(V,  DoubleFactory2D.dense.diagonal(eigenvalues)),
                        algebra.inverse(V)
                );

                return new SymmetricMatrix(newHessian.toArray());
            }

            private double[][] getNumericalHessianCheckForward(int dim, double[] x) {
                double[][] hessian = new double[dim][dim];
                double[][] gradientMatrix = new double[dim][dim];
                final double[] gradient = transform.updateGradientLogDensity(hessianWrtParameterProvider.getGradientLogDensity(),
                        hessianWrtParameterProvider.getParameter().getParameterValues(), 0, dim);
                double[] oldUntransformedPosition = hessianWrtParameterProvider.getParameter().getParameterValues();
                double[] oldTransformedPosition = transform.transform(oldUntransformedPosition, 0, dim);
                double[] h = new double[dim];
                for (int i = 0; i < dim; i++) {
                    h[i] = MachineAccuracy.SQRT_EPSILON * (Math.abs(x[i]) + 1.0);
                    hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(oldTransformedPosition[i] + h[i]));
                    double[] gradientTmp = hessianWrtParameterProvider.getGradientLogDensity();
                    gradientMatrix[i] = transform.updateGradientLogDensity(gradientTmp, hessianWrtParameterProvider.getParameter().getParameterValues(),
                            0, dim);
                    hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(oldTransformedPosition[i]));
                }
                for (int i = 0; i < dim; i++) {
                    for (int j = i; j < dim; j++) {
                        hessian[i][j] = hessian[j][i] = (gradientMatrix[j][i] - gradient[i]) / (2.0 * h[j]) + (gradientMatrix[i][j] - gradient[j]) / (2.0 * h[i]);
                    }
                }
                return hessian;
            }

            private double[][] getNumericalHessianCheck(MultivariateFunction f, double[] x) {
                double[][] hessian = new double[f.getNumArguments()][f.getNumArguments()];
                for (int i = 0; i < f.getNumArguments(); i++) {
                    double hi = MachineAccuracy.SQRT_SQRT_EPSILON * (Math.abs(x[i]) + 1.0);
                    double oldXi = x[i];
                    double f__ = f.evaluate(x);
                    x[i] = oldXi + hi;
                    double fp_ = f.evaluate(x);
                    x[i] = oldXi - hi;
                    double fm_ = f.evaluate(x);
                    x[i] = oldXi + 2.0 * hi;
                    double fpp = f.evaluate(x);
                    x[i] = oldXi - 2.0 * hi;
                    double fmm = f.evaluate(x);
                    hessian[i][i] = (-fpp + 16.0 * fp_ - 30.0 * f__ + 16.0 * fm_ - fmm) / (12.0 * hi * hi);
                    for (int j = i + 1; j < f.getNumArguments(); j++) { //forward difference approximation
                        double hj = MachineAccuracy.SQRT_SQRT_EPSILON * (Math.abs(x[j]) + 1.0);
                        double oldXj = x[j];
                        x[i] = oldXi + hi;
                        x[j] = oldXj + hj;
                        fpp = f.evaluate(x);
                        x[i] = oldXi + hi;
                        x[j] = oldXj - hj;
                        double fpm = f.evaluate(x);
                        x[i] = oldXi - hi;
                        x[j] = oldXj + hj;
                        double fmp = f.evaluate(x);
                        x[i] = oldXi - hi;
                        x[j] = oldXj - hj;
                        fmm = f.evaluate(x);
                        x[i] = oldXi;
                        x[j] = oldXj;
                        hessian[i][j] = hessian[j][i] = (fpp - fpm - fmp + fmm) / (4.0 * hi * hj);
                    }
                }
                return hessian;
            }

            private MultivariateFunction numeric1 = new MultivariateFunction() {
                @Override
                public double evaluate(double[] argument) {

                    for (int i = 0; i < argument.length; ++i) {
                        hessianWrtParameterProvider.getParameter().setParameterValue(i, Math.exp(argument[i]));
                    }

                    return hessianWrtParameterProvider.getLikelihood().getLogLikelihood();
                }

                @Override
                public int getNumArguments() {
                    return hessianWrtParameterProvider.getDimension();
                }

                @Override
                public double getLowerBound(int n) {
                    return Double.NEGATIVE_INFINITY;
                }

                @Override
                public double getUpperBound(int n) {
                    return Double.POSITIVE_INFINITY;
                }
            };
        }

    }
}
