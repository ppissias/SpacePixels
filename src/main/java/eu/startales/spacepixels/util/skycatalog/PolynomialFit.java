/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.PlateSolutionCorrection;

import java.util.ArrayList;
import java.util.List;

/** Least-squares fit of a polynomial displacement, leaving out points more than 3 sigma off (twice). */
final class PolynomialFit {

    private PolynomialFit() {
    }

    /**
     * The polynomial of this order that best moves each position by its displacement; null when there are fewer
     * than three points per coefficient or the points do not determine it.
     */
    static PlateSolutionCorrection.Polynomial fit(List<double[]> positions, List<double[]> displacements, int order,
                                                  double cx, double cy, double scale) {
        PlateSolutionCorrection.Polynomial polynomial = new PlateSolutionCorrection.Polynomial();
        polynomial.order = order;
        polynomial.cx = cx;
        polynomial.cy = cy;
        polynomial.scale = scale;
        int n = PlateSolutionCorrection.Polynomial.terms(order);
        List<Integer> use = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            use.add(i);
        }
        for (int iteration = 0; iteration < 3; iteration++) {
            if (use.size() < n * 3) {
                return null;
            }
            double[][] normal = new double[n][n];
            double[] rx = new double[n];
            double[] ry = new double[n];
            for (int i : use) {
                double[] b = PlateSolutionCorrection.Polynomial.basis(order,
                        (positions.get(i)[0] - cx) / scale, (positions.get(i)[1] - cy) / scale);
                for (int p = 0; p < n; p++) {
                    rx[p] += b[p] * displacements.get(i)[0];
                    ry[p] += b[p] * displacements.get(i)[1];
                    for (int q = 0; q < n; q++) {
                        normal[p][q] += b[p] * b[q];
                    }
                }
            }
            polynomial.ax = solve(copy(normal), rx);
            polynomial.ay = solve(copy(normal), ry);
            if (polynomial.ax == null || polynomial.ay == null) {
                return null;
            }
            if (iteration == 2) {
                break;
            }
            List<Double> residuals = new ArrayList<>();
            for (int i : use) {
                double[] d = polynomial.delta(positions.get(i)[0], positions.get(i)[1]);
                residuals.add(Math.hypot(displacements.get(i)[0] - d[0], displacements.get(i)[1] - d[1]));
            }
            List<Double> sorted = new ArrayList<>(residuals);
            sorted.sort(null);
            double limit = 3 * 1.4826 * sorted.get(sorted.size() / 2) + 0.1;
            List<Integer> kept = new ArrayList<>();
            for (int k = 0; k < use.size(); k++) {
                if (residuals.get(k) <= limit) {
                    kept.add(use.get(k));
                }
            }
            use = kept;
        }
        return polynomial;
    }

    private static double[][] copy(double[][] matrix) {
        double[][] copy = new double[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    /** Gaussian elimination with partial pivoting; null when the matrix is singular. */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[] x = b.clone();
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            double[] row = a[col];
            a[col] = a[pivot];
            a[pivot] = row;
            double value = x[col];
            x[col] = x[pivot];
            x[pivot] = value;
            for (int r = col + 1; r < n; r++) {
                double factor = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= factor * a[col][c];
                }
                x[r] -= factor * x[col];
            }
        }
        for (int r = n - 1; r >= 0; r--) {
            double sum = x[r];
            for (int c = r + 1; c < n; c++) {
                sum -= a[r][c] * x[c];
            }
            x[r] = sum / a[r][r];
        }
        return x;
    }
}
