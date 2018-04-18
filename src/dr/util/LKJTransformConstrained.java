/*
 * LKJTransform.java
 *
 * Copyright (c) 2002-2018 Alexei Drummond, Andrew Rambaut and Marc Suchard
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

package dr.util;

import dr.math.matrixAlgebra.CholeskyDecomposition;
import dr.math.matrixAlgebra.IllegalDimension;
import dr.math.matrixAlgebra.Matrix;
import dr.math.matrixAlgebra.SymmetricMatrix;

import static dr.math.matrixAlgebra.SymmetricMatrix.compoundCorrelationSymmetricMatrix;
import static dr.math.matrixAlgebra.SymmetricMatrix.extractUpperTriangular;

/**
 * @author Paul Bastide
 */

public class LKJTransformConstrained extends Transform.MultivariableTransform {

    private static boolean DEBUG = false;

    // LKJ transform with CPCs constrained between -1 and 1.

    private int dim;

    public LKJTransformConstrained(int dim) {
        this.dim = dim;
    }

    public double[] transform(double[] values) {
        return transform(values, 0, values.length);
    }

    public double[] inverse(double[] values) {
        return inverse(values, 0, values.length);
    }

    // "Cholesky" transform from Stan manual
    @Override
    public double[] transform(double[] values, int from, int to) {
        assert from == 0 && to == values.length : "The transform function can only be applied to the whole array of values.";
        assert dim * (dim - 1) / 2 == values.length : "The transform function can only be applied to the whole array of values.";
        for (int k = 0; k < dim; k++) {
            assert values[k] <= 1.0 && values[k] >= -1.0 : "CPCs must be between -1.0 and 1.0";
        }

        Matrix W = new Matrix(dim, dim);

        double acc;
        double temp;
        W.set(0, 0, 1.0);
        for (int j = 1; j < dim; j++) {
            acc = 1.0;
            for (int i = 0; i < j + 1; i++) {
                temp = getUpperTriangular(values, i, j);
                W.set(i, j, temp * acc);
                acc *= Math.sqrt(1 - Math.pow(temp, 2));
            }
        }

        SymmetricMatrix R = W.transposedProduct();

        if (DEBUG) {
            System.err.println("Z: " + compoundCorrelationSymmetricMatrix(values, dim));
            System.err.println("R: " + R);
            try {
                if (!R.isPD()) {
                    throw new RuntimeException("The LKJ transform should produce a Positive Definite matrix.");
                }
            } catch (IllegalDimension illegalDimension) {
                illegalDimension.printStackTrace();
            }
        }

        return extractUpperTriangular(R);
    }

    @Override
    public double[] inverse(double[] values, int from, int to) {
        assert from == 0 && to == values.length : "The transform function can only be applied to the whole array of values.";

        SymmetricMatrix R = compoundCorrelationSymmetricMatrix(values, dim);
        double[][] L;
        try {
            L = new CholeskyDecomposition(R).getL();
        } catch (IllegalDimension illegalDimension) {
            throw new RuntimeException("Unable to decompose matrix in LKJ inverse transform.");
        }

        double[] results = new double[dim * (dim - 1) / 2];

        double acc;
        double temp;
        for (int j = 1; j < dim; j++) {
            acc = 1.0;
            for (int i = 0; i < j; i++) {
                temp = L[j][i] / acc;
                setUpperTriangular(results, i, j, temp);
                acc *= Math.sqrt(1 - Math.pow(temp, 2));
            }
        }

        if (DEBUG) {
            System.err.println("R: " + compoundCorrelationSymmetricMatrix(values, dim));
            System.err.println("L: " + new SymmetricMatrix(L));
            System.err.println("Z: " + compoundCorrelationSymmetricMatrix(results, dim));
        }

        return results;
    }

    private double getUpperTriangular(double[] values, int i, int j) {
        assert i <= j;
        if (i == j) return 1.0; // Z_{ii} = 1 by convention.
        return values[i * (2 * dim - i - 1) / 2 + (j - i - 1)];
    }

    private void setUpperTriangular(double[] values, int i, int j, double val) {
        assert i < j;
        values[i * (2 * dim - i - 1) / 2 + (j - i - 1)] = val;
    }

    @Override
    public double[] inverse(double[] values, int from, int to, double sum) {
        throw new RuntimeException("Not relevant for the LKJ transform.");
    }

    public String getTransformName() {
        return "LKJTransform";
    }

    @Override
    public double[] updateGradientLogDensity(double[] gradient, double[] value, int from, int to) {
        throw new RuntimeException("Not yet implemented");
    }

    @Override
    public double[] gradientInverse(double[] values, int from, int to) {
        throw new RuntimeException("Not yet implemented");
    }

    @Override
    public double getLogJacobian(double[] values, int from, int to) {
        assert from == 0 && to == values.length
                : "The logJacobian function can only be applied to the whole array of values.";
        double logJacobian = 0;
        int k = 0;
        for (int i = 0; i < dim - 2; i++) { // Sizes of conditioning sets
            for (int j = i + 1; j < dim; j++) {
                logJacobian += (dim - i - 2) * Math.log(1.0 - Math.pow(values[k], 2));
                k++;
            }
        }
        return 0.5 * logJacobian;
    }

    // Unused recursive transformation (more numerical errors)
    public double[] transformRecursion(double[] values, int from, int to) {
        assert from == 0 && to == values.length : "The transform function can only be applied to the whole array of values.";
        assert dim * (dim - 1) / 2 == values.length : "The transform function can only be applied to the whole array of values.";
        for (int k = 0; k < dim; k++) {
            assert values[k] <= 1.0 && values[k] >= -1.0 : "CPCs must be between -1.0 and 1.0";
        }

        SymmetricMatrix R = compoundCorrelationSymmetricMatrix(values, dim);

        for (int i = 1; i < dim - 1; i++) {
            recursion(R, i);
        }

        return extractUpperTriangular(R);
    }

    public double[] inverseRecursion(double[] values, int from, int to) {
        assert from == 0 && to == values.length
                : "The transform function can only be applied to the whole array of values.";

        SymmetricMatrix Z = compoundCorrelationSymmetricMatrix(values, dim);

        for (int i = 1; i < dim - 1; i++) {
            recursionInverse(Z, i);
        }

        if (DEBUG) {
            try {
                if (!Z.isPD()) {
                    throw new RuntimeException("The LKJ transform should produce a Positive Definite matrix.");
                }
            } catch (IllegalDimension illegalDimension) {
                illegalDimension.printStackTrace();
            }
        }

        return extractUpperTriangular(Z);
    }

    private void recursion(SymmetricMatrix R, int iota) {
        for (int i = dim - 1; i > iota - 1; i--) {
            for (int j = i + 1; j < dim; j++) {
                R.setSymmetric(i, j, recursionFormula(R, i, j));
            }
        }
    }

    private double recursionFormula(SymmetricMatrix R, int i, int j) {
        double Rimi = R.component(i - 1, i);
        double Rimj = R.component(i - 1, j);

        return R.component(i, j) * Math.sqrt((1 - Rimi * Rimi) * (1 - Rimj * Rimj)) + Rimi * Rimj;
    }

    private void recursionInverse(SymmetricMatrix Z, int iota) {
        for (int i = dim - 1; i > iota - 1; i--) {
            for (int j = i + 1; j < dim; j++) {
                Z.setSymmetric(i, j, recursionInverseFormula(Z, i, j));
            }
        }
    }

    private double recursionInverseFormula(SymmetricMatrix Z, int i, int j) {
        double Zimi = Z.component(i - 1, i);
        double Zimj = Z.component(i - 1, j);

        return (Z.component(i, j) - Zimi * Zimj) / Math.sqrt((1 - Zimi * Zimi) * (1 - Zimj * Zimj));
    }
}