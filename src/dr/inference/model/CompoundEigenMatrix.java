/*
 * CompoundEigenMatrix.java
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

package dr.inference.model;

import dr.math.matrixAlgebra.WrappedMatrix;
import dr.xml.*;
import org.ejml.data.DenseMatrix64F;
import org.ejml.ops.CommonOps;

import static dr.math.matrixAlgebra.missingData.MissingOps.wrapDiagonal;
import static dr.math.matrixAlgebra.missingData.MissingOps.wrapSpherical;

/**
 * @author Marc Suchard
 * @author Paul Bastide
 */
public class CompoundEigenMatrix extends AbstractTransformedCompoundMatrix {

    private final DenseMatrix64F transformedMatrix;

    private boolean compositionKnown = false;

    private final DenseMatrix64F temp;

    public CompoundEigenMatrix(Parameter eigenValues, MatrixParameter eigenVectors) {
        super(eigenValues, eigenVectors);
        // Matrices
        temp = new DenseMatrix64F(dim, dim);
        transformedMatrix = new DenseMatrix64F(dim, dim);
        computeTransformedMatrix();
    }

    private void computeTransformedMatrix() {
        DenseMatrix64F baseMatrix = wrapSpherical(offDiagonalParameter.getParameterValues(), 0, dim);
        DenseMatrix64F diagonalMatrix = wrapDiagonal(diagonalParameter.getParameterValues(), 0, dim);

        CommonOps.mult(baseMatrix, diagonalMatrix, temp);
        CommonOps.invert(baseMatrix);
        CommonOps.mult(temp, baseMatrix, transformedMatrix);

        compositionKnown = true;
    }


    @Override
    public double getParameterValue(int row, int col) {
        if (!compositionKnown) computeTransformedMatrix();
        return transformedMatrix.get(row, col);
    }

    public double[] updateGradientDiagonal(double[] vecX) {
        throw new RuntimeException("Not yet implemented");
    }

    public double[] updateGradientOffDiagonal(double[] gradient) {
        throw new RuntimeException("Not yet implemented");
    }

    public double[] getEigenValues() {
        return diagonalParameter.getParameterValues();
    }

    @Override
    public String getReport() {
        return new WrappedMatrix.ArrayOfArray(getParameterAsMatrix()).toString();
    }

    //************************************************************************
    // Parser
    //************************************************************************

    public static final String NAME = "compoundEigenMatrix";
    private static final String EIGEN_VALUES = "eigenValues";
    private static final String EIGEN_VECTORS = "eigenVectors";

    public static XMLObjectParser PARSER = new AbstractXMLObjectParser() {

        public String getParserName() {
            return NAME;
        }

        public Object parseXMLObject(XMLObject xo) throws XMLParseException {

            Parameter eigenValues = (Parameter) xo.getElementFirstChild(EIGEN_VALUES);
            MatrixParameter eigenVectors = (MatrixParameter) xo.getElementFirstChild(EIGEN_VECTORS);

            if (eigenVectors.getDimension() != (eigenValues.getDimension() * (eigenValues.getDimension() - 1))) {
                throw new XMLParseException("Invalid parameter dimensions in `" + xo.getId() + "'");
            }

            return new CompoundEigenMatrix(eigenValues, eigenVectors);
        }

        //************************************************************************
        // AbstractXMLObjectParser implementation
        //************************************************************************

        public String getParserDescription() {
            return "A compound matrix parametrized by its eigen values and vectors.";
        }

        public XMLSyntaxRule[] getSyntaxRules() {
            return rules;
        }

        private XMLSyntaxRule[] rules = new XMLSyntaxRule[]{
                new ElementRule(EIGEN_VALUES, new XMLSyntaxRule[]{new ElementRule(Parameter.class)}),
                new ElementRule(EIGEN_VECTORS, new XMLSyntaxRule[]{new ElementRule(MatrixParameter.class)}),
        };

        public Class getReturnType() {
            return CompoundEigenMatrix.class;
        }
    };
}
