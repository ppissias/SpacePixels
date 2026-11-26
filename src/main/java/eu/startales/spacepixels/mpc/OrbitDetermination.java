package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.util.SkyMath;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Clean-room BSD preliminary heliocentric orbit determination engine for optical astrometric observations.
 * Implements classical and modern textbook methods without external dependencies:
 * <ul>
 *   <li><b>Short single-night arcs (&lt; 0.4 days) and weakly-constrained 2-night arcs:</b> Topocentric
 *       $(\rho_1, \rho_2)$ Statistical Ranging (Virtanen, Muinonen &amp; Bowell 2001) using an exact universal-variable
 *       Lambert solver across the first and last observations (preserving exact diurnal parallax and light-time),
 *       evaluated with a Solar-System orbital prior to prevent runaway noise-chasing along the Line of Variation,
 *       producing a realistic nominal orbit, honest 1-sigma spreads, a probabilistic NEO score {@code P(NEO)}, and
 *       representative sample orbits for 3D uncertainty visualisation.</li>
 *   <li><b>Well-constrained multi-night arcs (&ge; 0.4 days):</b> Classical Gauss IOD and universal-variable
 *       Herget/Lambert grid search followed by damped Levenberg–Marquardt weighted differential correction with an
 *       RK4 N-body planetary perturber model (Sun + Venus, Earth+Moon, Mars, Jupiter, Saturn), light-time correction,
 *       covariance 1-sigma uncertainties, and Monte Carlo {@code P(NEO)}.</li>
 * </ul>
 * Intended for preliminary visual triage and short-to-medium optical arcs (not long multi-year ephemerides or
 * Earth-orbiting artificial satellites).
 */
public final class OrbitDetermination {
    /** Identifier placed in {@link FindOrb.Orbit#version()} for built-in fits. */
    public static final String SOLVER_TAG = "SpacePixels built-in";

    /** Gravitational parameter of the Sun in AU^3 / day^2 (k^2, Gaussian gravitational constant squared). */
    private static final double MU_SUN = 0.01720209895 * 0.01720209895;
    /** Inverse planetary mass ratios (M_sun / M_planet) for N-body perturbation of Venus, Earth+Moon, Mars, Jupiter, Saturn. */
    private static final int[] PERTURBER_INDICES = {1, 2, 3, 4, 5};
    private static final double[] PERTURBER_GM = {
            MU_SUN / 408_523.71,     // Venus
            MU_SUN / 328_900.56,     // Earth + Moon
            MU_SUN / 3_098_708.0,    // Mars
            MU_SUN / 1_047.3486,     // Jupiter
            MU_SUN / 3_497.898       // Saturn
    };

    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC);

    /**
     * One astrometric observation paired with the observing site where it was acquired (from that night's FITS
     * headers, else configured coordinates, or null for geocentric). Allows fitting multi-night linked arcs taken from
     * different sites without requiring an IAU observatory code.
     */
    public record SitedObservation(MpcObservation observation, ObservingSite site) { }

    /**
     * Full outcome of a built-in orbit determination: the nominal fitted {@link FindOrb.Orbit} (compatible with the 3D
     * viewer and UI summaries) plus optional alternative sample orbits consistent with the observations (for 3D
     * uncertainty cloud rendering on short or weakly constrained arcs).
     */
    public record Solution(FindOrb.Orbit orbit, List<FindOrb.Orbit> sampleOrbits) { }

    /** Preprocessed astrometric observation in J2000 ecliptic coordinates. */
    private record Obs(String isoTime, double jdUtc, double jdTt, double raDeg, double decDeg,
                       double sigmaRaArcsec, double sigmaDecArcsec,
                       double[] obsPos, double[] los) { }

    /** One heliocentric 3D state vector (position in AU, velocity in AU/day) at epoch. */
    private record State(double[] r, double[] v) { }

    /** One evaluated candidate orbit with weighted chi-squared, prior weight, and Keplerian elements. */
    private record Candidate(State state, double chi2, double prior, double[] elem, double rho1, double rho2) { }

    private OrbitDetermination() { }

    /**
     * Fits a preliminary heliocentric orbit to {@code observations} observed from {@code site} (from FITS headers or
     * configured coordinates, or null for geocentric). Requires at least three observations spanning a non-zero time
     * interval.
     *
     * @throws IllegalArgumentException when fewer than three usable observations are supplied or no valid orbit fits
     */
    public static Solution fit(List<MpcObservation> observations, ObservingSite site) {
        if (observations == null || observations.size() < 3) {
            throw new IllegalArgumentException("At least three observations are required to fit an orbit.");
        }
        List<SitedObservation> sited = new ArrayList<>(observations.size());
        for (MpcObservation o : observations) {
            sited.add(new SitedObservation(o, site));
        }
        return fitSited(sited);
    }

    /**
     * Fits a preliminary heliocentric orbit to observations that may each come from a different {@link ObservingSite}
     * (e.g., linked nights imaged from different locations, each using its own FITS-header or configured coordinates,
     * with no IAU observatory code required).
     *
     * @throws IllegalArgumentException when fewer than three usable observations are supplied or no valid orbit fits
     */
    public static Solution fitSited(List<SitedObservation> observations) {
        if (observations == null || observations.size() < 3) {
            throw new IllegalArgumentException("At least three observations are required to fit an orbit.");
        }
        List<SitedObservation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingLong(s -> s.observation().midpointMillis()));

        List<Obs> obs = new ArrayList<>(sorted.size());
        for (SitedObservation item : sorted) {
            MpcObservation o = item.observation();
            Instant inst = Instant.ofEpochMilli(o.midpointMillis());
            double jdUtc = OrbitGeometry.instantToJd(inst);
            double jdTt = EarthEphemeris.utcToTt(jdUtc);
            double sigRa = Double.isFinite(o.rmsRaArcsec()) && o.rmsRaArcsec() >= 0.1 ? o.rmsRaArcsec() : 0.5;
            double sigDec = Double.isFinite(o.rmsDecArcsec()) && o.rmsDecArcsec() >= 0.1 ? o.rmsDecArcsec() : 0.5;
            double[] rObs = EarthEphemeris.observerPositionJ2000(jdUtc, jdTt, item.site());
            double[] u = EarthEphemeris.lineOfSightEcliptic(o.raDegrees(), o.decDegrees());
            obs.add(new Obs(ISO_UTC.format(inst), jdUtc, jdTt, o.raDegrees(), o.decDegrees(), sigRa, sigDec, rObs, u));
        }
        double spanDays = obs.getLast().jdTt() - obs.getFirst().jdTt();
        if (spanDays <= 1e-6) {
            throw new IllegalArgumentException("Observations must span a non-zero time interval.");
        }
        double epochJd = 0.5 * (obs.getFirst().jdTt() + obs.getLast().jdTt());
        if (spanDays < 0.40) {
            return fitStatisticalRanging(obs, epochJd, false);
        }
        return fitMultiNight(obs, epochJd);
    }

    // =========================================================================
    // Topocentric (rho1, rho2) Statistical Ranging (single-night & weak 2-night arcs)
    // =========================================================================

    private static Solution fitStatisticalRanging(List<Obs> obs, double epochJd, boolean perturbed) {
        List<List<Obs>> nights = clusterNights(obs);
        Obs first;
        Obs last;
        if (nights.size() == 1) {
            Obs rawFirst = obs.getFirst();
            Obs rawLast = obs.getLast();
            // On a single-night arc, anchor the Lambert endpoints to the robust linear sky fit across all observations
            // so random noise on frame 1 or frame N does not bias the velocity of every ranged candidate.
            if (obs.size() >= 4) {
                double[] attr = fitRobustLinearSkyTrack(obs, epochJd);
                first = withSmoothedLos(rawFirst, attr, epochJd);
                last = withSmoothedLos(rawLast, attr, epochJd);
            } else {
                first = rawFirst;
                last = rawLast;
            }
        } else {
            // On a multi-night arc, anchor Lambert to the smoothed midpoint of the first night and the smoothed
            // midpoint of the last night (using real observation timestamps and topocentric site positions) so an
            // outlier at the start or end of a night cannot drag every ranged candidate off the true sky track.
            first = smoothedNightMidpoint(nights.getFirst());
            last = smoothedNightMidpoint(nights.getLast());
        }
        double dtSpan = last.jdTt() - first.jdTt();
        if (dtSpan <= 1e-6) {
            first = obs.getFirst();
            last = obs.getLast();
            dtSpan = last.jdTt() - first.jdTt();
        }

        double[] earthVel = EarthEphemeris.earthVelocityJ2000(epochJd);
        // Angular motion across the arc gives an exact physical upper bound on rho1 for bound heliocentric orbits.
        double cosSep = Math.max(-1.0, Math.min(1.0, dot(first.los(), last.los())));
        double skyRateRadPerDay = Math.acos(cosSep) / Math.max(1e-6, dtSpan);

        // Multi-night arcs have a narrower chi2 valley in (rho1, rhoDot), so use a denser range-rate grid.
        boolean multiNight = nights.size() >= 2;
        int rhoSteps = multiNight ? 85 : 70;
        int rdSteps = multiNight ? 41 : 17;
        double logRhoMin = Math.log(0.004), logRhoMax = Math.log(20.0);
        List<Candidate> candidates = new ArrayList<>();
        double bestChi2 = Double.POSITIVE_INFINITY;
        double dof = Math.max(1.0, 2.0 * obs.size() - 4.0);
        double corrFactor = sameNightCorrelationFactor(nights);

        for (int i = 0; i < rhoSteps; i++) {
            double rho1 = Math.exp(logRhoMin + (logRhoMax - logRhoMin) * i / (rhoSteps - 1));
            double[] r1 = add(first.obsPos(), scale(first.los(), rho1));
            double r1m = norm(r1);
            if (r1m < 0.05) continue;

            // Admissible range-rate bounds for a bound heliocentric orbit: |v_helio|^2 < 2*mu/r1.
            double vEsc = Math.sqrt(2.0 * MU_SUN / r1m);
            double vSkyApprox = rho1 * skyRateRadPerDay;
            if (vSkyApprox > vEsc * 1.45) continue; // Physically unbound even with Earth velocity offset

            // Approximate tangent heliocentric velocity to center the admissible rhoDot interval tightly.
            double[] uDotApprox = scale(sub(last.los(), first.los()), 1.0 / dtSpan);
            double[] vTrans = add(earthVel, scale(uDotApprox, rho1));
            double vDotU = dot(vTrans, first.los());
            double vPerp2 = dot(vTrans, vTrans) - vDotU * vDotU;
            double vEsc2 = 2.0 * MU_SUN / r1m;
            if (vPerp2 >= vEsc2 * 0.998) continue;
            double halfSpan = Math.sqrt(Math.max(0.0, vEsc2 - vPerp2));
            double rhoDotCenter = -vDotU;

            for (int j = 0; j < rdSteps; j++) {
                double frac = -0.92 + 1.84 * j / (rdSteps - 1);
                double rhoDot = rhoDotCenter + frac * halfSpan;
                double rho2 = rho1 + rhoDot * dtSpan;
                if (rho2 <= 0.002 || rho2 > 80.0) continue;

                // Exact topocentric Lambert solution passing through first & last anchors with diurnal parallax + light-time.
                State st = stateFromHergetPair(first, last, rho1, rho2, epochJd);
                if (st == null) continue;
                double[] elem = stateToElements(st.r(), st.v(), epochJd);
                if (elem == null || !Double.isFinite(elem[0]) || elem[0] <= 0.15 || elem[0] > 60.0 || elem[1] >= 0.98) {
                    continue;
                }
                // Two-body chi2 is exact to <0.01" across short arcs and keeps dense ranging fast; final orbit uses N-body.
                double chi2 = chiSquared(obs, epochJd, st, false);
                if (!Double.isFinite(chi2)) continue;
                if (chi2 < bestChi2) bestChi2 = chi2;
                double prior = orbitalPrior(elem);
                candidates.add(new Candidate(st, chi2, prior, elem, rho1, rho2));
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Could not find a bound preliminary orbit for these observations.");
        }

        // Convert chi2 + Solar System population prior into Bayesian posterior weights, accounting for correlated
        // same-night frames (Farnocchia et al. 2015 debiasing) so dense single-night burst sampling does not
        // over-weight airmass or guiding drift.
        double chi2Scale = Math.max(1.0, bestChi2 / dof) * corrFactor;
        Candidate best = null;
        double bestPost = -1.0;
        List<Candidate> weighted = new ArrayList<>();
        for (Candidate c : candidates) {
            double dChi2 = (c.chi2() - bestChi2) / chi2Scale;
            if (dChi2 > 16.0) continue;
            double post = c.prior() * Math.exp(-0.5 * dChi2);
            if (post <= 0) continue;
            weighted.add(new Candidate(c.state(), c.chi2(), post, c.elem(), c.rho1(), c.rho2()));
            if (post > bestPost) {
                bestPost = post;
                best = c;
            }
        }
        if (best == null) best = candidates.getFirst();

        // Polish only the 4 sky-plane anchor angles at fixed (rho1, rho2) so residuals are minimal without sliding
        // away from the Bayesian posterior range optimum along the unconstrained Line of Variation.
        State nominal = refineSkyAnchorsFixedRange(obs, first, last, best.rho1(), best.rho2(), epochJd, best.state(), perturbed);
        double[] nomElem = stateToElements(nominal.r(), nominal.v(), epochJd);
        if (nomElem == null || nomElem[1] >= 0.99) {
            nominal = best.state();
            nomElem = best.elem();
        }

        double sumW = 0.0, neoW = 0.0;
        for (Candidate c : weighted) {
            sumW += c.prior();
            if (c.elem()[6] <= 1.3) neoW += c.prior();
        }
        double pNeo = sumW > 0 ? 100.0 * neoW / sumW : (nomElem[6] <= 1.3 ? 100.0 : 0.0);
        double[] sigmas = ensembleSigmas(weighted, nomElem);
        List<FindOrb.Orbit> samples = selectSampleOrbits(weighted, epochJd, 18);
        FindOrb.Orbit orbit = buildOrbitRecord(obs, epochJd, nominal, nomElem, sigmas, pNeo, perturbed);
        return new Solution(orbit, samples);
    }

    /**
     * Gentle Solar-System dynamical mixture prior for under-constrained short arcs (main-belt + NEO + outer-belt
     * populations): favors realistic low-to-moderate eccentricity main-belt orbits when observations cannot rule them
     * out, while giving genuine high-eccentricity / high-inclination NEOs an unpenalized population component so real
     * NEOs are neither biased downward in eccentricity nor suppressed in {@code P(NEO)}.
     */
    private static double orbitalPrior(double[] elem) {
        double a = elem[0], e = elem[1], inc = elem[2], q = elem[6];
        double logA = Math.log(a);
        // Component 1: Main-belt asteroid population (a ~ 2.7 AU, low-to-moderate e and i).
        double pMb = Math.exp(-0.5 * Math.pow((logA - Math.log(2.7)) / 0.42, 2.0))
                * Math.exp(-0.5 * Math.pow(e / 0.22, 2.0))
                * Math.exp(-0.5 * Math.pow(inc / 15.0, 2.0));
        // Component 2: Near-Earth & planet-crossing population (broad eccentricity & inclination).
        double pNeo = 0.18
                * Math.exp(-0.5 * Math.pow((logA - Math.log(1.6)) / 0.60, 2.0))
                * Math.exp(-0.5 * Math.pow((e - 0.45) / 0.40, 2.0))
                * Math.exp(-0.5 * Math.pow(inc / 32.0, 2.0));
        // Component 3: Outer belt / Centaur / Jupiter-family tail.
        double pOuter = 0.05
                * Math.exp(-0.5 * Math.pow((logA - Math.log(5.5)) / 0.75, 2.0))
                * Math.exp(-0.5 * Math.pow(e / 0.40, 2.0))
                * Math.exp(-0.5 * Math.pow(inc / 25.0, 2.0));
        double qTaper = q < 0.10 ? Math.max(0.05, q / 0.10) : 1.0;
        return Math.max(1e-12, (pMb + pNeo + pOuter) * qTaper);
    }

    /**
     * Polishes the 4 sky-plane anchor angles (RA/Dec of {@code first} and {@code last}) at fixed topocentric ranges
     * {@code (rho1, rho2)} via damped Levenberg–Marquardt, eliminating midpoint-linearization error while strictly
     * preventing drift along the unconstrained range Line of Variation.
     */
    private static State refineSkyAnchorsFixedRange(List<Obs> obs, Obs first, Obs last, double rho1, double rho2,
                                                    double epochJd, State fallback, boolean perturbed) {
        double[] angles = {first.raDeg(), first.decDeg(), last.raDeg(), last.decDeg()};
        double cosD1 = Math.max(0.05, Math.cos(Math.toRadians(angles[1])));
        double cosD2 = Math.max(0.05, Math.cos(Math.toRadians(angles[3])));
        State cur = stateFromHergetPair(first, last, rho1, rho2, epochJd);
        if (cur == null) return fallback;
        double curChi2 = chiSquared(obs, epochJd, cur, perturbed);
        if (!Double.isFinite(curChi2)) return fallback;

        int m = 2 * obs.size();
        double lambda = 1e-2;
        double hDeg = 0.05 / 3600.0; // 0.05 arcsec finite-difference step
        double maxShiftDeg = 4.0 / 3600.0;
        double[] origin = angles.clone();

        for (int iter = 0; iter < 8; iter++) {
            double[] res = weightedResiduals(obs, epochJd, cur, perturbed);
            if (res == null) break;
            double[][] jMat = new double[m][4];
            for (int col = 0; col < 4; col++) {
                double step = (col == 0 ? hDeg / cosD1 : col == 2 ? hDeg / cosD2 : hDeg);
                double orig = angles[col];
                angles[col] = orig + step;
                State sp = stateFromAngles(first, last, angles, rho1, rho2, epochJd);
                angles[col] = orig - step;
                State sm = stateFromAngles(first, last, angles, rho1, rho2, epochJd);
                angles[col] = orig;
                if (sp == null || sm == null) return cur;
                double[] rp = weightedResiduals(obs, epochJd, sp, perturbed);
                double[] rm = weightedResiduals(obs, epochJd, sm, perturbed);
                if (rp == null || rm == null) return cur;
                for (int r = 0; r < m; r++) jMat[r][col] = (rp[r] - rm[r]) / (2.0 * step);
            }
            double[][] aMat = new double[4][4];
            double[] bVec = new double[4];
            for (int r = 0; r < m; r++) {
                for (int i = 0; i < 4; i++) {
                    double ji = jMat[r][i];
                    bVec[i] -= ji * res[r];
                    for (int k = i; k < 4; k++) aMat[i][k] += ji * jMat[r][k];
                }
            }
            for (int i = 0; i < 4; i++) for (int k = 0; k < i; k++) aMat[i][k] = aMat[k][i];

            boolean accepted = false;
            for (int tries = 0; tries < 6; tries++) {
                double[][] damped = new double[4][4];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(aMat[i], 0, damped[i], 0, 4);
                    damped[i][i] += lambda * Math.max(1e-12, aMat[i][i]);
                }
                double[] dx = solveLinear(damped, bVec);
                if (dx == null) { lambda *= 10.0; continue; }
                double[] next = new double[4];
                boolean inBounds = true;
                for (int i = 0; i < 4; i++) {
                    next[i] = angles[i] + dx[i];
                    double shift = Math.abs(next[i] - origin[i]) * (i == 0 ? cosD1 : i == 2 ? cosD2 : 1.0);
                    if (shift > maxShiftDeg) inBounds = false;
                }
                if (!inBounds) { lambda *= 10.0; continue; }
                State nextSt = stateFromAngles(first, last, next, rho1, rho2, epochJd);
                if (nextSt == null) { lambda *= 10.0; continue; }
                double nextChi2 = chiSquared(obs, epochJd, nextSt, perturbed);
                if (Double.isFinite(nextChi2) && nextChi2 < curChi2) {
                    double rel = (curChi2 - nextChi2) / Math.max(1e-12, curChi2);
                    angles = next;
                    cur = nextSt;
                    curChi2 = nextChi2;
                    lambda = Math.max(1e-6, lambda * 0.3);
                    accepted = true;
                    if (rel < 1e-6) return cur;
                    break;
                }
                lambda *= 8.0;
            }
            if (!accepted) break;
        }
        return cur;
    }

    private static State stateFromAngles(Obs first, Obs last, double[] angles, double rho1, double rho2, double epochJd) {
        double ra1 = SkyMath.normalizeDegrees(angles[0]);
        double dec1 = Math.max(-89.999, Math.min(89.999, angles[1]));
        double ra2 = SkyMath.normalizeDegrees(angles[2]);
        double dec2 = Math.max(-89.999, Math.min(89.999, angles[3]));
        Obs a1 = new Obs(first.isoTime(), first.jdUtc(), first.jdTt(), ra1, dec1, first.sigmaRaArcsec(), first.sigmaDecArcsec(),
                first.obsPos(), EarthEphemeris.lineOfSightEcliptic(ra1, dec1));
        Obs a2 = new Obs(last.isoTime(), last.jdUtc(), last.jdTt(), ra2, dec2, last.sigmaRaArcsec(), last.sigmaDecArcsec(),
                last.obsPos(), EarthEphemeris.lineOfSightEcliptic(ra2, dec2));
        return stateFromHergetPair(a1, a2, rho1, rho2, epochJd);
    }

    // =========================================================================
    // Multi-night solver: Gauss + Herget/Lambert IOD + N-body Levenberg-Marquardt
    // =========================================================================

    private static Solution fitMultiNight(List<Obs> obs, double epochJd) {
        List<State> seeds = new ArrayList<>();
        // 1) Classical Gauss IOD on first, middle, last observations.
        Obs o1 = obs.getFirst();
        Obs o2 = pickMiddleObservation(obs);
        Obs o3 = obs.getLast();
        seeds.addAll(gaussIod(o1, o2, o3, epochJd));

        // 2) Universal-variable Lambert / Herget range grid search across (rho1, rho2).
        State hergetSeed = hergetIod(obs, epochJd);
        if (hergetSeed != null) seeds.add(hergetSeed);

        if (seeds.isEmpty()) {
            return fitStatisticalRanging(obs, epochJd, true);
        }

        State bestState = null;
        double bestObj = Double.POSITIVE_INFINITY;
        for (State seed : seeds) {
            State twoBody = refineDamped(obs, epochJd, seed, false, 1e-3, 35);
            double c2 = chiSquared(obs, epochJd, twoBody, false);
            double[] el = stateToElements(twoBody.r(), twoBody.v(), epochJd);
            if (el == null) continue;
            double penalty = el[1] > 1.5 ? 25.0 * (el[1] - 1.5) : 0.0;
            if (c2 + penalty < bestObj) {
                bestObj = c2 + penalty;
                bestState = twoBody;
            }
        }
        if (bestState == null) bestState = seeds.getFirst();

        // Final N-body perturbed Levenberg-Marquardt refinement (Sun + Venus, Earth+Moon, Mars, Jupiter, Saturn).
        State refined = refineDamped(obs, epochJd, bestState, true, 1e-4, 40);
        if (chiSquared(obs, epochJd, refined, true) > chiSquared(obs, epochJd, bestState, true) * 1.05) {
            refined = bestState;
        }
        double[] elem = stateToElements(refined.r(), refined.v(), epochJd);
        if (elem == null) {
            return fitStatisticalRanging(obs, epochJd, true);
        }

        CovarianceResult cov = computeCovariance(obs, epochJd, refined, elem, true);
        // Check whether the unconstrained 6-DOF solution is genuinely well-constrained by the data, or whether a
        // 2-night / noisy arc allowed least-squares to slide along an ill-conditioned Line-of-Variation valley.
        List<List<Obs>> nights = clusterNights(obs);
        double sigmaA = cov.elemSigmas()[0];
        double sigmaE = cov.elemSigmas()[1];
        double sigmaPeri = cov.elemSigmas()[4];
        boolean poorlyConstrained = nights.size() <= 2
                || !Double.isFinite(sigmaE) || sigmaE > 0.15
                || !Double.isFinite(sigmaPeri) || sigmaPeri > 25.0
                || (elem[1] < 1.0 && (!Double.isFinite(sigmaA) || sigmaA > 0.25 * Math.abs(elem[0])))
                || elem[1] > 1.05;

        if (poorlyConstrained) {
            // Compare unconstrained chi2 against Bayesian Statistical Ranging along the exact (rho1, rho2) LOV!
            try {
                Solution ranged = fitStatisticalRanging(obs, epochJd, true);
                double unconstrainedChi2 = chiSquared(obs, epochJd, refined, true);
                double dof = Math.max(1.0, 2.0 * obs.size() - 6.0);
                double corrFactor = sameNightCorrelationFactor(nights);
                double noiseScale = Math.max(1.0, unconstrainedChi2 / dof) * corrFactor;
                // Recompute chi2 of the ranged orbit directly from its residuals.
                double rangedChi2 = 0.0;
                for (int k = 0; k < obs.size() && k < ranged.orbit().residuals().size(); k++) {
                    var res = ranged.orbit().residuals().get(k);
                    double wr = res.raArcsec() / obs.get(k).sigmaRaArcsec();
                    double wd = res.decArcsec() / obs.get(k).sigmaDecArcsec();
                    rangedChi2 += wr * wr + wd * wd;
                }
                double deltaChi2Scaled = (rangedChi2 - unconstrainedChi2) / noiseScale;
                boolean tightUnconstrained = Double.isFinite(sigmaE) && sigmaE < 0.12
                        && (elem[1] >= 1.0 || (Double.isFinite(sigmaA) && sigmaA < 0.15 * Math.abs(elem[0])));
                // Keep unconstrained 6-DOF over Statistical Ranging only when the data strongly demand it (> 3 sigma)
                // or on a genuinely tightly-constrained 2-night object (e.g., fast close NEO with large parallax/curvature).
                if (deltaChi2Scaled < 9.0 && !tightUnconstrained) {
                    return ranged;
                }
            } catch (IllegalArgumentException ignored) {
                // No bound orbit fits (e.g., genuine hyperbolic comet): keep the unconstrained N-body LM fit!
            }
        }

        FindOrb.Orbit orbit = buildOrbitRecord(obs, epochJd, refined, elem, cov.elemSigmas(), cov.pNeo(), true);
        return new Solution(orbit, cov.samples());
    }

    // =========================================================================
    // Classical Gauss Initial Orbit Determination (3 observations)
    // =========================================================================

    private static List<State> gaussIod(Obs o1, Obs o2, Obs o3, double epochJd) {
        List<State> results = new ArrayList<>();
        double tau1 = o1.jdTt() - o2.jdTt();
        double tau3 = o3.jdTt() - o2.jdTt();
        double tau = tau3 - tau1;
        if (Math.abs(tau1) < 1e-5 || Math.abs(tau3) < 1e-5 || Math.abs(tau) < 1e-5) return results;

        double[] u1 = o1.los(), u2 = o2.los(), u3 = o3.los();
        double[] p1 = cross(u2, u3), p2 = cross(u1, u3), p3 = cross(u1, u2);
        double d0 = dot(u1, p1);
        if (Math.abs(d0) < 1e-12) return results;

        double d11 = dot(o1.obsPos(), p1), d12 = dot(o1.obsPos(), p2), d13 = dot(o1.obsPos(), p3);
        double d21 = dot(o2.obsPos(), p1), d22 = dot(o2.obsPos(), p2), d23 = dot(o2.obsPos(), p3);
        double d31 = dot(o3.obsPos(), p1), d32 = dot(o3.obsPos(), p2), d33 = dot(o3.obsPos(), p3);

        double aCoef = (-d12 * (tau3 / tau) + d22 + d32 * (tau1 / tau)) / d0;
        double bCoef = (d12 * (tau3 * tau3 - tau * tau) * (tau3 / tau)
                + d32 * (tau * tau - tau1 * tau1) * (tau1 / tau)) / (6.0 * d0);
        double eScalar = dot(o2.obsPos(), u2);
        double rObs2Sq = dot(o2.obsPos(), o2.obsPos());
        double polyA = -(aCoef * aCoef + 2.0 * aCoef * eScalar + rObs2Sq);
        double polyB = -2.0 * MU_SUN * bCoef * (aCoef + eScalar);
        double polyC = -MU_SUN * MU_SUN * bCoef * bCoef;

        List<Double> roots = findPositiveGaussRoots(polyA, polyB, polyC);
        for (double r2Mag : roots) {
            double r2Cube = r2Mag * r2Mag * r2Mag;
            double rho2 = aCoef + MU_SUN * bCoef / r2Cube;
            if (rho2 <= 0.002 || rho2 > 80.0) continue;

            double c1 = (tau3 / tau) * (1.0 + MU_SUN * (tau * tau - tau3 * tau3) / (6.0 * r2Cube));
            double c3 = (-tau1 / tau) * (1.0 + MU_SUN * (tau * tau - tau1 * tau1) / (6.0 * r2Cube));
            double rho1 = (c1 * d11 - d21 + c3 * d31) / (c1 * d0);
            double rho3 = (c1 * d13 - d23 + c3 * d33) / (c3 * d0);
            if (rho1 <= 0.002 || rho3 <= 0.002) continue;

            double[] r1 = add(o1.obsPos(), scale(u1, rho1));
            double[] r2 = add(o2.obsPos(), scale(u2, rho2));
            double[] r3 = add(o3.obsPos(), scale(u3, rho3));

            double t1 = o1.jdTt() - rho1 / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
            double t2 = o2.jdTt() - rho2 / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
            double t3 = o3.jdTt() - rho3 / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
            double[] v2 = herrickGibbsOrLambert(r1, r2, r3, t1, t2, t3);
            if (v2 == null) continue;
            State atEpoch = propagateTwoBody(new State(r2, v2), epochJd - t2);
            if (atEpoch != null) results.add(atEpoch);
        }
        return results;
    }

    private static List<Double> findPositiveGaussRoots(double a, double b, double c) {
        List<Double> roots = new ArrayList<>();
        int steps = 300;
        double logMin = Math.log(0.12), logMax = Math.log(55.0);
        double prevR = Math.exp(logMin);
        double prevF = gaussPoly(prevR, a, b, c);
        for (int k = 1; k <= steps; k++) {
            double r = Math.exp(logMin + (logMax - logMin) * k / steps);
            double f = gaussPoly(r, a, b, c);
            if (prevF * f <= 0.0) {
                double lo = prevR, hi = r, fLo = prevF;
                for (int it = 0; it < 50; it++) {
                    double mid = 0.5 * (lo + hi);
                    double fMid = gaussPoly(mid, a, b, c);
                    if (fLo * fMid <= 0.0) hi = mid;
                    else { lo = mid; fLo = fMid; }
                }
                double root = 0.5 * (lo + hi);
                if (roots.stream().noneMatch(existing -> Math.abs(existing - root) < 1e-4)) {
                    roots.add(root);
                }
            }
            prevR = r;
            prevF = f;
        }
        return roots;
    }

    private static double gaussPoly(double r, double a, double b, double c) {
        double r2 = r * r;
        double r3 = r2 * r;
        double r6 = r3 * r3;
        return r6 * r2 + a * r6 + b * r3 + c;
    }

    private static double[] herrickGibbsOrLambert(double[] r1, double[] r2, double[] r3, double t1, double t2, double t3) {
        State fromLambert = solveLambert(r1, r3, t3 - t1);
        if (fromLambert != null) {
            State atMid = propagateTwoBody(fromLambert, t2 - t1);
            if (atMid != null) return atMid.v();
        }
        double dt31 = t3 - t1, dt32 = t3 - t2, dt21 = t2 - t1;
        double r1m = norm(r1), r2m = norm(r2), r3m = norm(r3);
        double c1 = -dt32 * (1.0 / (dt21 * dt31) + MU_SUN / (12.0 * r1m * r1m * r1m));
        double c2 = (dt32 - dt21) * (1.0 / (dt21 * dt32) + MU_SUN / (12.0 * r2m * r2m * r2m));
        double c3 = dt21 * (1.0 / (dt32 * dt31) + MU_SUN / (12.0 * r3m * r3m * r3m));
        return new double[]{
                c1 * r1[0] + c2 * r2[0] + c3 * r3[0],
                c1 * r1[1] + c2 * r2[1] + c3 * r3[1],
                c1 * r1[2] + c2 * r2[2] + c3 * r3[2]
        };
    }

    // =========================================================================
    // Herget / Universal-Variable Lambert Grid Search
    // =========================================================================

    private static State hergetIod(List<Obs> obs, double epochJd) {
        Obs first = obs.getFirst();
        Obs last = obs.getLast();
        double dt = last.jdTt() - first.jdTt();
        if (dt <= 1e-4) return null;

        double[] rGrid = {0.03, 0.07, 0.15, 0.30, 0.55, 0.90, 1.40, 2.00, 2.80, 4.0, 6.5, 12.0, 25.0};
        double[] ratioGrid = {0.82, 0.92, 0.98, 1.0, 1.02, 1.08, 1.18};

        double bestRho1 = Double.NaN, bestRho2 = Double.NaN;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double rho1 : rGrid) {
            for (double ratio : ratioGrid) {
                double rho2 = rho1 * ratio;
                double score = evaluateHergetPair(obs, first, last, rho1, rho2, epochJd);
                if (score < bestScore) {
                    bestScore = score;
                    bestRho1 = rho1;
                    bestRho2 = rho2;
                }
            }
        }
        if (!Double.isFinite(bestRho1)) return null;

        double lr1 = Math.log(bestRho1), lr2 = Math.log(bestRho2);
        double step = 0.20;
        for (int iter = 0; iter < 30; iter++) {
            boolean improved = false;
            for (int d1 = -1; d1 <= 1; d1++) {
                for (int d2 = -1; d2 <= 1; d2++) {
                    if (d1 == 0 && d2 == 0) continue;
                    double nr1 = lr1 + d1 * step;
                    double nr2 = lr2 + d2 * step;
                    if (nr1 < Math.log(0.004) || nr1 > Math.log(60.0) || nr2 < Math.log(0.004) || nr2 > Math.log(60.0)) {
                        continue;
                    }
                    double s = evaluateHergetPair(obs, first, last, Math.exp(nr1), Math.exp(nr2), epochJd);
                    if (s < bestScore) {
                        bestScore = s;
                        lr1 = nr1;
                        lr2 = nr2;
                        improved = true;
                    }
                }
            }
            if (!improved) step *= 0.5;
            if (step < 1e-4) break;
        }
        return stateFromHergetPair(first, last, Math.exp(lr1), Math.exp(lr2), epochJd);
    }

    private static double evaluateHergetPair(List<Obs> obs, Obs first, Obs last, double rho1, double rho2, double epochJd) {
        State st = stateFromHergetPair(first, last, rho1, rho2, epochJd);
        if (st == null) return Double.POSITIVE_INFINITY;
        double[] el = stateToElements(st.r(), st.v(), epochJd);
        if (el == null || el[1] > 2.5) return Double.POSITIVE_INFINITY;
        return chiSquared(obs, epochJd, st, false) + (el[1] > 0.95 ? 10.0 * (el[1] - 0.95) : 0.0);
    }

    private static State stateFromHergetPair(Obs first, Obs last, double rho1, double rho2, double epochJd) {
        double[] r1 = add(first.obsPos(), scale(first.los(), rho1));
        double[] r2 = add(last.obsPos(), scale(last.los(), rho2));
        double t1 = first.jdTt() - rho1 / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
        double t2 = last.jdTt() - rho2 / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
        State s1 = solveLambert(r1, r2, t2 - t1);
        if (s1 == null) return null;
        return propagateTwoBody(s1, epochJd - t1);
    }

    // =========================================================================
    // Universal-Variable Lambert Solver (Bate, Mueller & White / Vallado)
    // =========================================================================

    static State solveLambert(double[] r1, double[] r2, double dt) {
        if (dt <= 0) return null;
        double r1m = norm(r1), r2m = norm(r2);
        if (r1m < 1e-6 || r2m < 1e-6) return null;
        double cosDnu = Math.max(-1.0, Math.min(1.0, dot(r1, r2) / (r1m * r2m)));
        double a = Math.sqrt(r1m * r2m * (1.0 + cosDnu));
        if (a < 1e-10) return null;

        double sqrtMu = Math.sqrt(MU_SUN);
        double psiLo = -4.0 * Math.PI * Math.PI;
        double psiHi = 4.0 * Math.PI * Math.PI * 0.99;
        double psi = 0.0;

        for (int iter = 0; iter < 60; iter++) {
            double c2 = stumpffC2(psi);
            double c3 = stumpffC3(psi);
            double y = r1m + r2m + a * (psi * c3 - 1.0) / Math.sqrt(c2);
            if (y < 0.0) {
                psiLo = psi;
                psi = 0.5 * (psiLo + psiHi);
                continue;
            }
            double chi = Math.sqrt(y / c2);
            double tCalc = (chi * chi * chi * c3 + a * Math.sqrt(y)) / sqrtMu;
            if (Math.abs(tCalc - dt) <= 1e-9 * Math.max(1.0, dt)) {
                double f = 1.0 - y / r1m;
                double g = a * Math.sqrt(y / MU_SUN);
                if (Math.abs(g) < 1e-12) return null;
                double[] v1 = scale(sub(r2, scale(r1, f)), 1.0 / g);
                return new State(r1.clone(), v1);
            }
            if (tCalc < dt) psiLo = psi; else psiHi = psi;
            psi = 0.5 * (psiLo + psiHi);
        }
        return null;
    }

    // =========================================================================
    // Universal-Variable Two-Body & RK4 N-Body Propagators
    // =========================================================================

    static State propagateTwoBody(State s0, double dt) {
        if (Math.abs(dt) < 1e-12) return new State(s0.r().clone(), s0.v().clone());
        double[] r0 = s0.r(), v0 = s0.v();
        double r0m = norm(r0), v0sq = dot(v0, v0);
        if (r0m < 1e-8) return null;
        double alpha = 2.0 / r0m - v0sq / MU_SUN;
        double rDotV = dot(r0, v0);
        double sqrtMu = Math.sqrt(MU_SUN);

        double chi;
        if (alpha > 1e-6) {
            chi = sqrtMu * dt * alpha;
        } else if (alpha < -1e-6) {
            double aNeg = 1.0 / alpha;
            double term = -2.0 * MU_SUN * alpha * dt / (rDotV + Math.copySign(Math.sqrt(-MU_SUN * aNeg) * (1.0 - r0m * alpha), dt));
            chi = Math.copySign(Math.sqrt(-aNeg) * Math.log(Math.max(1.0001, Math.abs(term))), dt);
        } else {
            chi = sqrtMu * dt / r0m;
        }

        for (int iter = 0; iter < 50; iter++) {
            double psi = chi * chi * alpha;
            double c2 = stumpffC2(psi);
            double c3 = stumpffC3(psi);
            double chi2 = chi * chi;
            double r = chi2 * c2 + (rDotV / sqrtMu) * chi * (1.0 - psi * c3) + r0m * (1.0 - psi * c2);
            double f = chi2 * chi * c3 + (rDotV / sqrtMu) * chi2 * c2 + r0m * chi * (1.0 - psi * c3) - sqrtMu * dt;
            if (Math.abs(f) < 1e-12 * Math.max(1.0, Math.abs(sqrtMu * dt))) break;
            if (Math.abs(r) < 1e-12) return null;
            double step = f / r;
            chi -= step;
            if (Math.abs(step) < 1e-12) break;
        }

        double psi = chi * chi * alpha;
        double c2 = stumpffC2(psi);
        double c3 = stumpffC3(psi);
        double f = 1.0 - (chi * chi / r0m) * c2;
        double g = dt - (chi * chi * chi / sqrtMu) * c3;
        double[] r = add(scale(r0, f), scale(v0, g));
        double rm = norm(r);
        if (!Double.isFinite(rm) || rm < 1e-8) return null;
        double fDot = (sqrtMu / (rm * r0m)) * chi * (psi * c3 - 1.0);
        double gDot = 1.0 - (chi * chi / rm) * c2;
        double[] v = add(scale(r0, fDot), scale(v0, gDot));
        return new State(r, v);
    }

    private static State propagatePerturbed(State s0, double t0, double tTarget) {
        double dtTotal = tTarget - t0;
        if (Math.abs(dtTotal) < 1e-11) return new State(s0.r().clone(), s0.v().clone());
        double maxStep = Math.min(2.0, Math.max(0.25, norm(s0.r()) * 0.5));
        int steps = Math.max(1, (int) Math.ceil(Math.abs(dtTotal) / maxStep));
        double h = dtTotal / steps;
        double[] y = {s0.r()[0], s0.r()[1], s0.r()[2], s0.v()[0], s0.v()[1], s0.v()[2]};
        double t = t0;
        for (int s = 0; s < steps; s++) {
            double[] k1 = nBodyDeriv(t, y);
            double[] k2 = nBodyDeriv(t + 0.5 * h, addScaled(y, k1, 0.5 * h));
            double[] k3 = nBodyDeriv(t + 0.5 * h, addScaled(y, k2, 0.5 * h));
            double[] k4 = nBodyDeriv(t + h, addScaled(y, k3, h));
            for (int i = 0; i < 6; i++) {
                y[i] += (h / 6.0) * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]);
            }
            t += h;
        }
        return new State(new double[]{y[0], y[1], y[2]}, new double[]{y[3], y[4], y[5]});
    }

    private static double[] nBodyDeriv(double jdTt, double[] y) {
        double x = y[0], yy = y[1], z = y[2];
        double r2 = x * x + yy * yy + z * z;
        double r3 = r2 * Math.sqrt(r2);
        double invSun = -MU_SUN / Math.max(1e-18, r3);
        double ax = x * invSun, ay = yy * invSun, az = z * invSun;
        for (int p = 0; p < PERTURBER_INDICES.length; p++) {
            int idx = PERTURBER_INDICES[p];
            double[] rp = idx == OrbitGeometry.EARTH_INDEX
                    ? EarthEphemeris.earthPositionJ2000(jdTt)
                    : OrbitGeometry.planetPosition3D(idx, jdTt);
            double dx = rp[0] - x, dy = rp[1] - yy, dz = rp[2] - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            double d3 = d2 * Math.sqrt(d2);
            double rp2 = rp[0] * rp[0] + rp[1] * rp[1] + rp[2] * rp[2];
            double rp3 = rp2 * Math.sqrt(rp2);
            double gm = PERTURBER_GM[p];
            if (d3 > 1e-15) {
                ax += gm * (dx / d3 - rp[0] / rp3);
                ay += gm * (dy / d3 - rp[1] / rp3);
                az += gm * (dz / d3 - rp[2] / rp3);
            }
        }
        return new double[]{y[3], y[4], y[5], ax, ay, az};
    }

    private static double stumpffC2(double psi) {
        if (psi > 1e-6) {
            double s = Math.sqrt(psi);
            return (1.0 - Math.cos(s)) / psi;
        }
        if (psi < -1e-6) {
            double s = Math.sqrt(-psi);
            return (Math.cosh(s) - 1.0) / (-psi);
        }
        return 0.5 - psi / 24.0 + psi * psi / 720.0;
    }

    private static double stumpffC3(double psi) {
        if (psi > 1e-6) {
            double s = Math.sqrt(psi);
            return (s - Math.sin(s)) / (psi * s);
        }
        if (psi < -1e-6) {
            double s = Math.sqrt(-psi);
            return (Math.sinh(s) - s) / ((-psi) * s);
        }
        return 1.0 / 6.0 - psi / 120.0 + psi * psi / 5040.0;
    }

    // =========================================================================
    // Levenberg-Marquardt Weighted Differential Correction
    // =========================================================================

    private static State refineDamped(List<Obs> obs, double epochJd, State initial, boolean perturbed,
                                      double initialLambda, int maxIter) {
        double[] x = {initial.r()[0], initial.r()[1], initial.r()[2], initial.v()[0], initial.v()[1], initial.v()[2]};
        double curChi2 = chiSquared(obs, epochJd, toState(x), perturbed);
        double lambda = initialLambda;
        int m = 2 * obs.size();
        double[] steps = {1e-6, 1e-6, 1e-6, 1e-8, 1e-8, 1e-8};

        for (int iter = 0; iter < maxIter; iter++) {
            double[] res = weightedResiduals(obs, epochJd, toState(x), perturbed);
            if (res == null) break;
            double[][] jMat = new double[m][6];
            for (int col = 0; col < 6; col++) {
                double h = steps[col] * Math.max(1.0, Math.abs(x[col]));
                double orig = x[col];
                x[col] = orig + h;
                double[] rPlus = weightedResiduals(obs, epochJd, toState(x), perturbed);
                x[col] = orig - h;
                double[] rMinus = weightedResiduals(obs, epochJd, toState(x), perturbed);
                x[col] = orig;
                if (rPlus == null || rMinus == null) return toState(x);
                for (int row = 0; row < m; row++) {
                    jMat[row][col] = (rPlus[row] - rMinus[row]) / (2.0 * h);
                }
            }
            double[][] aMat = new double[6][6];
            double[] bVec = new double[6];
            for (int r = 0; r < m; r++) {
                for (int i = 0; i < 6; i++) {
                    double ji = jMat[r][i];
                    bVec[i] -= ji * res[r];
                    for (int k = i; k < 6; k++) aMat[i][k] += ji * jMat[r][k];
                }
            }
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < i; k++) aMat[i][k] = aMat[k][i];
            }

            boolean accepted = false;
            for (int tries = 0; tries < 8; tries++) {
                double[][] damped = new double[6][6];
                for (int i = 0; i < 6; i++) {
                    System.arraycopy(aMat[i], 0, damped[i], 0, 6);
                    damped[i][i] += lambda * Math.max(1e-12, aMat[i][i]);
                }
                double[] dx = solveLinear(damped, bVec);
                if (dx == null) {
                    lambda *= 10.0;
                    continue;
                }
                double[] next = new double[6];
                for (int i = 0; i < 6; i++) next[i] = x[i] + dx[i];
                double nextChi2 = chiSquared(obs, epochJd, toState(next), perturbed);
                if (Double.isFinite(nextChi2) && nextChi2 < curChi2) {
                    double relImprove = (curChi2 - nextChi2) / Math.max(1e-12, curChi2);
                    x = next;
                    curChi2 = nextChi2;
                    lambda = Math.max(1e-9, lambda * 0.3);
                    accepted = true;
                    if (relImprove < 1e-8) return toState(x);
                    break;
                }
                lambda = Math.min(1e10, lambda * 8.0);
            }
            if (!accepted) break;
        }
        return toState(x);
    }

    private static double[] predictResidualArcsec(Obs o, double epochJd, State s0, boolean perturbed) {
        double dt = o.jdTt() - epochJd;
        double lt = 0.0;
        double[] rhoVec = null;
        for (int it = 0; it < 2; it++) {
            State st = perturbed
                    ? propagatePerturbed(s0, epochJd, epochJd + dt - lt)
                    : propagateTwoBody(s0, dt - lt);
            if (st == null) return null;
            rhoVec = sub(st.r(), o.obsPos());
            lt = norm(rhoVec) / EarthEphemeris.SPEED_OF_LIGHT_AU_PER_DAY;
        }
        double[] eq = EarthEphemeris.eclipticToEquatorial(rhoVec);
        double rho = norm(eq);
        if (rho < 1e-10) return null;
        double raCalc = SkyMath.normalizeDegrees(Math.toDegrees(Math.atan2(eq[1], eq[0])));
        double decCalc = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, eq[2] / rho))));
        double dRa = SkyMath.normalizeSignedDegrees(o.raDeg() - raCalc) * Math.cos(Math.toRadians(o.decDeg())) * 3600.0;
        double dDec = (o.decDeg() - decCalc) * 3600.0;
        return new double[]{dRa, dDec};
    }

    private static double[] weightedResiduals(List<Obs> obs, double epochJd, State s0, boolean perturbed) {
        double[] w = new double[2 * obs.size()];
        for (int k = 0; k < obs.size(); k++) {
            double[] r = predictResidualArcsec(obs.get(k), epochJd, s0, perturbed);
            if (r == null) return null;
            w[2 * k] = -r[0] / obs.get(k).sigmaRaArcsec();
            w[2 * k + 1] = -r[1] / obs.get(k).sigmaDecArcsec();
        }
        return w;
    }

    private static double chiSquared(List<Obs> obs, double epochJd, State s0, boolean perturbed) {
        double[] w = weightedResiduals(obs, epochJd, s0, perturbed);
        if (w == null) return Double.POSITIVE_INFINITY;
        double sum = 0.0;
        for (double v : w) sum += v * v;
        return sum;
    }

    // =========================================================================
    // Covariance, Uncertainties, Earth MOID & Orbital Elements Conversion
    // =========================================================================

    private record CovarianceResult(double[] elemSigmas, double pNeo, List<FindOrb.Orbit> samples) { }

    private static CovarianceResult computeCovariance(List<Obs> obs, double epochJd, State nominal,
                                                      double[] nomElem, boolean perturbed) {
        int m = 2 * obs.size();
        double[] x = {nominal.r()[0], nominal.r()[1], nominal.r()[2], nominal.v()[0], nominal.v()[1], nominal.v()[2]};
        double[] steps = {1e-6, 1e-6, 1e-6, 1e-8, 1e-8, 1e-8};
        double[][] jMat = new double[m][6];
        for (int col = 0; col < 6; col++) {
            double h = steps[col] * Math.max(1.0, Math.abs(x[col]));
            double orig = x[col];
            x[col] = orig + h;
            double[] rp = weightedResiduals(obs, epochJd, toState(x), perturbed);
            x[col] = orig - h;
            double[] rm = weightedResiduals(obs, epochJd, toState(x), perturbed);
            x[col] = orig;
            if (rp == null || rm == null) return new CovarianceResult(new double[7], nomElem[6] <= 1.3 ? 100.0 : 0.0, List.of());
            for (int r = 0; r < m; r++) jMat[r][col] = (rp[r] - rm[r]) / (2.0 * h);
        }
        double[][] normal = new double[6][6];
        for (int r = 0; r < m; r++) {
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 6; k++) normal[i][k] += jMat[r][i] * jMat[r][k];
            }
        }
        for (int i = 0; i < 6; i++) normal[i][i] += 1e-12 * Math.max(1.0, normal[i][i]);
        double[][] covState = invertSymmetric(normal);
        if (covState == null) return new CovarianceResult(new double[7], nomElem[6] <= 1.3 ? 100.0 : 0.0, List.of());

        double chi2 = chiSquared(obs, epochJd, nominal, perturbed);
        double dof = Math.max(1.0, m - 6.0);
        double scale = Math.max(1.0, chi2 / dof);
        for (int i = 0; i < 6; i++) for (int k = 0; k < 6; k++) covState[i][k] *= scale;

        double[][] elemJac = new double[7][6];
        for (int col = 0; col < 6; col++) {
            double h = steps[col] * Math.max(1.0, Math.abs(x[col]));
            double orig = x[col];
            x[col] = orig + h;
            double[] ep = stateToElements(new double[]{x[0], x[1], x[2]}, new double[]{x[3], x[4], x[5]}, epochJd);
            x[col] = orig - h;
            double[] em = stateToElements(new double[]{x[0], x[1], x[2]}, new double[]{x[3], x[4], x[5]}, epochJd);
            x[col] = orig;
            if (ep == null || em == null) continue;
            for (int el = 0; el < 7; el++) {
                double diff = (el >= 2 && el <= 5) ? SkyMath.normalizeSignedDegrees(ep[el] - em[el]) : (ep[el] - em[el]);
                elemJac[el][col] = diff / (2.0 * h);
            }
        }
        double[] sigmas = new double[7];
        for (int el = 0; el < 7; el++) {
            double var = 0.0;
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 6; k++) var += elemJac[el][i] * covState[i][k] * elemJac[el][k];
            }
            sigmas[el] = var > 0 && Double.isFinite(var) ? Math.sqrt(var) : Double.NaN;
        }

        double[][] chol = cholesky(covState);
        double pNeo = nomElem[6] <= 1.3 ? 100.0 : 0.0;
        List<FindOrb.Orbit> samples = new ArrayList<>();
        if (chol != null) {
            java.util.Random rng = new java.util.Random(42L);
            int total = 120, valid = 0, neo = 0;
            for (int draw = 0; draw < total; draw++) {
                double[] z = new double[6];
                for (int i = 0; i < 6; i++) z[i] = rng.nextGaussian();
                double[] xs = x.clone();
                for (int i = 0; i < 6; i++) {
                    for (int k = 0; k <= i; k++) xs[i] += chol[i][k] * z[k];
                }
                double[] es = stateToElements(new double[]{xs[0], xs[1], xs[2]}, new double[]{xs[3], xs[4], xs[5]}, epochJd);
                if (es == null || !Double.isFinite(es[6]) || es[6] <= 0.01) continue;
                valid++;
                if (es[6] <= 1.3) neo++;
                if (samples.size() < 16 && es[1] < 1.8 && (!Double.isFinite(es[0]) || Math.abs(es[0]) < 80.0)) {
                    samples.add(lightweightOrbit(epochJd, es));
                }
            }
            if (valid > 0) pNeo = 100.0 * neo / valid;
        }
        return new CovarianceResult(sigmas, pNeo, List.copyOf(samples));
    }

    /**
     * Converts a heliocentric J2000 ecliptic state vector to classical Keplerian elements:
     * {@code {a (AU), e, i (deg), ascNode (deg), argPeri (deg), meanAnomaly (deg), q (AU), tpJd}}.
     */
    static double[] stateToElements(double[] r, double[] v, double epochJd) {
        double rm = norm(r), vm2 = dot(v, v);
        if (rm < 1e-8 || !Double.isFinite(rm) || !Double.isFinite(vm2)) return null;
        double[] h = cross(r, v);
        double hm = norm(h);
        if (hm < 1e-10) return null;
        double[] nVec = {-h[1], h[0], 0.0};
        double nm = norm(nVec);
        double[] eVec = sub(scale(cross(v, h), 1.0 / MU_SUN), scale(r, 1.0 / rm));
        double e = norm(eVec);
        double energy = 0.5 * vm2 - MU_SUN / rm;
        double a = Math.abs(energy) > 1e-12 ? -MU_SUN / (2.0 * energy) : Double.NaN;
        double p = hm * hm / MU_SUN;
        double q = p / (1.0 + e);
        double inc = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, h[2] / hm))));

        double ascNode = nm > 1e-10 ? SkyMath.normalizeDegrees(Math.toDegrees(Math.atan2(nVec[1], nVec[0]))) : 0.0;
        double argPeri;
        if (nm > 1e-10 && e > 1e-9) {
            double cosW = Math.max(-1.0, Math.min(1.0, dot(nVec, eVec) / (nm * e)));
            argPeri = Math.toDegrees(Math.acos(cosW));
            if (eVec[2] < 0.0) argPeri = 360.0 - argPeri;
        } else if (e > 1e-9) {
            argPeri = SkyMath.normalizeDegrees(Math.toDegrees(Math.atan2(eVec[1], eVec[0])));
        } else {
            argPeri = 0.0;
        }

        double nu;
        if (e > 1e-9) {
            double cosNu = Math.max(-1.0, Math.min(1.0, dot(eVec, r) / (e * rm)));
            nu = Math.acos(cosNu);
            if (dot(r, v) < 0.0) nu = 2.0 * Math.PI - nu;
        } else if (nm > 1e-10) {
            double cosU = Math.max(-1.0, Math.min(1.0, dot(nVec, r) / (nm * rm)));
            nu = Math.acos(cosU);
            if (r[2] < 0.0) nu = 2.0 * Math.PI - nu;
        } else {
            nu = Math.atan2(r[1], r[0]);
        }

        double meanAnomalyDeg;
        double tpJd;
        double gaussK = Math.sqrt(MU_SUN);
        if (e < 1.0 - 1e-7 && Double.isFinite(a) && a > 0) {
            double ecc = 2.0 * Math.atan2(Math.sqrt(1.0 - e) * Math.sin(0.5 * nu), Math.sqrt(1.0 + e) * Math.cos(0.5 * nu));
            double mRad = ecc - e * Math.sin(ecc);
            meanAnomalyDeg = SkyMath.normalizeDegrees(Math.toDegrees(mRad));
            double nMean = gaussK / Math.pow(a, 1.5);
            double mSigned = mRad > Math.PI ? mRad - 2.0 * Math.PI : (mRad < -Math.PI ? mRad + 2.0 * Math.PI : mRad);
            tpJd = epochJd - mSigned / nMean;
        } else if (Math.abs(e - 1.0) <= 1e-7) {
            double d = Math.tan(0.5 * nu);
            double dtPeri = Math.sqrt(2.0) * Math.pow(q, 1.5) * (d + d * d * d / 3.0) / gaussK;
            meanAnomalyDeg = Double.NaN;
            tpJd = epochJd - dtPeri;
        } else {
            double aAbs = q / (e - 1.0);
            double tanhHalfH = Math.sqrt((e - 1.0) / (e + 1.0)) * Math.tan(0.5 * nu);
            if (Math.abs(tanhHalfH) >= 1.0) return null;
            double hypH = Math.log((1.0 + tanhHalfH) / (1.0 - tanhHalfH));
            double mh = e * Math.sinh(hypH) - hypH;
            double nHyp = gaussK / Math.pow(aAbs, 1.5);
            meanAnomalyDeg = Math.toDegrees(mh);
            tpJd = epochJd - mh / nHyp;
        }
        return new double[]{a, e, inc, ascNode, argPeri, meanAnomalyDeg, q, tpJd};
    }

    /**
     * Computes the Minimum Orbit Intersection Distance (Earth MOID) in AU between {@code orbit} and Earth's 3D orbit
     * using a coarse 2D search around both the full ellipse and the mutual nodal crossings, followed by coordinate
     * polish.
     */
    static double earthMoidAu(FindOrb.Orbit orbit, double epochJd) {
        int earthSteps = 96;
        double[][] earthPts = new double[earthSteps][];
        double[] earthJds = new double[earthSteps];
        for (int j = 0; j < earthSteps; j++) {
            earthJds[j] = epochJd + 365.25636 * j / earthSteps;
            earthPts[j] = OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, earthJds[j]);
        }

        double maxNu = orbit.bound() ? Math.PI : Math.min(Math.PI - 0.02, Math.acos(-1.0 / Math.max(1.0001, orbit.e())) - 0.02);
        int objSteps = 96;
        double bestNu = 0.0, bestEarthJd = epochJd, bestD2 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < objSteps; i++) {
            double nu = -maxNu + 2.0 * maxNu * i / objSteps;
            double[] po = OrbitGeometry.position(orbit, nu);
            for (int j = 0; j < earthSteps; j++) {
                double d2 = dist2(po, earthPts[j]);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestNu = nu;
                    bestEarthJd = earthJds[j];
                }
            }
        }

        List<double[]> starts = new ArrayList<>();
        starts.add(new double[]{bestNu, bestEarthJd});
        for (double nodeNu : new double[]{-Math.toRadians(orbit.argumentOfPerihelion()), Math.PI - Math.toRadians(orbit.argumentOfPerihelion())}) {
            if (1.0 + orbit.e() * Math.cos(nodeNu) <= 1e-4) continue;
            double[] po = OrbitGeometry.position(orbit, nodeNu);
            double closestJd = epochJd, nd2 = Double.POSITIVE_INFINITY;
            for (int j = 0; j < earthSteps; j++) {
                double d2 = dist2(po, earthPts[j]);
                if (d2 < nd2) { nd2 = d2; closestJd = earthJds[j]; }
            }
            starts.add(new double[]{nodeNu, closestJd});
        }

        double minD2 = bestD2;
        for (double[] start : starts) {
            double nu = start[0], eJd = start[1];
            double stepNu = 0.08, stepDays = 4.0;
            for (int it = 0; it < 36; it++) {
                boolean moved = false;
                for (int d1 = -1; d1 <= 1; d1++) {
                    for (int d2 = -1; d2 <= 1; d2++) {
                        if (d1 == 0 && d2 == 0) continue;
                        double nNu = nu + d1 * stepNu;
                        if (!orbit.bound() && Math.abs(nNu) >= maxNu) continue;
                        double nJd = eJd + d2 * stepDays;
                        double d2Val = dist2(OrbitGeometry.position(orbit, nNu),
                                OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, nJd));
                        if (d2Val < minD2) {
                            minD2 = d2Val;
                            nu = nNu;
                            eJd = nJd;
                            moved = true;
                        }
                    }
                }
                if (!moved) { stepNu *= 0.5; stepDays *= 0.5; }
                if (stepNu < 1e-6) break;
            }
        }
        return Math.sqrt(Math.max(0.0, minD2));
    }

    private static FindOrb.Orbit buildOrbitRecord(List<Obs> obs, double epochJd, State state, double[] elem,
                                                  double[] sigmas, double pNeo, boolean perturbed) {
        List<FindOrb.Residual> residuals = new ArrayList<>(obs.size());
        double sumSq = 0.0;
        for (Obs o : obs) {
            double[] r = predictResidualArcsec(o, epochJd, state, perturbed);
            double dRa = r == null ? 0.0 : r[0];
            double dDec = r == null ? 0.0 : r[1];
            residuals.add(new FindOrb.Residual(o.isoTime(), dRa, dDec, true));
            sumSq += dRa * dRa + dDec * dDec;
        }
        double rms = Math.sqrt(sumSq / Math.max(1, 2 * obs.size()));
        String epochIso = ISO_UTC.format(OrbitGeometry.jdToInstant(epochJd));
        Double tp = Double.isFinite(elem[7]) ? elem[7] : null;
        FindOrb.Orbit temp = new FindOrb.Orbit(epochIso, epochJd, elem[0], finiteOrNull(sigmas[0]),
                elem[1], finiteOrNull(sigmas[1]), elem[2], finiteOrNull(sigmas[2]),
                elem[3], finiteOrNull(sigmas[3]), elem[4], finiteOrNull(sigmas[4]),
                elem[5], finiteOrNull(sigmas[5]), elem[6], finiteOrNull(sigmas[6]),
                null, rms, 2 * obs.size(), pNeo, null, null, List.copyOf(residuals), SOLVER_TAG, tp);
        double moid = earthMoidAu(temp, epochJd);
        return new FindOrb.Orbit(epochIso, epochJd, elem[0], finiteOrNull(sigmas[0]),
                elem[1], finiteOrNull(sigmas[1]), elem[2], finiteOrNull(sigmas[2]),
                elem[3], finiteOrNull(sigmas[3]), elem[4], finiteOrNull(sigmas[4]),
                elem[5], finiteOrNull(sigmas[5]), elem[6], finiteOrNull(sigmas[6]),
                null, rms, 2 * obs.size(), pNeo, moid, null, List.copyOf(residuals), SOLVER_TAG, tp);
    }

    private static FindOrb.Orbit lightweightOrbit(double epochJd, double[] elem) {
        String epochIso = ISO_UTC.format(OrbitGeometry.jdToInstant(epochJd));
        Double tp = Double.isFinite(elem[7]) ? elem[7] : null;
        return new FindOrb.Orbit(epochIso, epochJd, elem[0], null, elem[1], null, elem[2], null,
                elem[3], null, elem[4], null, elem[5], null, elem[6], null,
                null, null, 0, null, null, null, List.of(), SOLVER_TAG, tp);
    }

    private static double[] ensembleSigmas(List<Candidate> weighted, double[] nomElem) {
        double[] sigmas = new double[7];
        Arrays.fill(sigmas, Double.NaN);
        double sumW = 0.0;
        for (Candidate c : weighted) sumW += c.prior();
        if (sumW <= 0) return sigmas;
        for (int el = 0; el < 7; el++) {
            double var = 0.0;
            for (Candidate c : weighted) {
                double d = (el >= 2 && el <= 5)
                        ? SkyMath.normalizeSignedDegrees(c.elem()[el] - nomElem[el])
                        : (c.elem()[el] - nomElem[el]);
                var += c.prior() * d * d;
            }
            sigmas[el] = Math.sqrt(var / sumW);
        }
        return sigmas;
    }

    private static List<FindOrb.Orbit> selectSampleOrbits(List<Candidate> weighted, double epochJd, int maxCount) {
        if (weighted.isEmpty()) return List.of();
        double peak = weighted.stream().mapToDouble(Candidate::prior).max().orElse(0.0);
        List<Candidate> plausible = new ArrayList<>(weighted.stream().filter(c -> c.prior() >= peak * 0.12).toList());
        if (plausible.isEmpty()) return List.of();
        plausible.sort(Comparator.comparingDouble(c -> c.elem()[0]));
        List<FindOrb.Orbit> out = new ArrayList<>();
        int count = Math.min(maxCount, plausible.size());
        for (int k = 0; k < count; k++) {
            int idx = count == 1 ? 0 : (int) Math.round(k * (plausible.size() - 1.0) / (count - 1.0));
            out.add(lightweightOrbit(epochJd, plausible.get(idx).elem()));
        }
        return List.copyOf(out);
    }

    /** Groups chronologically sorted observations into observing nights (splitting on gaps longer than 0.35 days). */
    private static List<List<Obs>> clusterNights(List<Obs> obs) {
        List<List<Obs>> nights = new ArrayList<>();
        List<Obs> current = new ArrayList<>();
        for (Obs o : obs) {
            if (!current.isEmpty() && o.jdTt() - current.getLast().jdTt() > 0.35) {
                nights.add(current);
                current = new ArrayList<>();
            }
            current.add(o);
        }
        if (!current.isEmpty()) nights.add(current);
        return nights;
    }

    /**
     * Correlation variance inflation factor for dense same-night tracklets (following Farnocchia et al. 2015
     * debiasing): up to 4 frames per night are treated as independent; denser bursts share same-night airmass and
     * guiding systematics so chi-squared scales by {@code avgObsPerNight / 4}.
     */
    private static double sameNightCorrelationFactor(List<List<Obs>> nights) {
        if (nights.isEmpty()) return 1.0;
        double total = 0.0;
        for (List<Obs> n : nights) total += n.size();
        double avgPerNight = total / nights.size();
        return Math.max(1.0, avgPerNight / 4.0);
    }

    /** Returns the middle observation of {@code night} with its line-of-sight smoothed by a robust linear fit. */
    private static Obs smoothedNightMidpoint(List<Obs> night) {
        Obs mid = night.get(night.size() / 2);
        if (night.size() < 3) return mid;
        double span = night.getLast().jdTt() - night.getFirst().jdTt();
        if (span <= 1e-6) return mid;
        double[] track = fitRobustLinearSkyTrack(night, mid.jdTt());
        return withSmoothedLos(mid, track, mid.jdTt());
    }

    private static Obs withSmoothedLos(Obs o, double[] attr, double epochJd) {
        double dt = o.jdTt() - epochJd;
        double raDeg = SkyMath.normalizeDegrees(attr[0] + attr[2] * dt);
        double decDeg = Math.max(-89.999, Math.min(89.999, attr[1] + attr[3] * dt));
        return new Obs(o.isoTime(), o.jdUtc(), o.jdTt(), raDeg, decDeg, o.sigmaRaArcsec(), o.sigmaDecArcsec(),
                o.obsPos(), EarthEphemeris.lineOfSightEcliptic(raDeg, decDeg));
    }

    /** Huber-reweighted linear sky fit {ra0Deg, dec0Deg, dRaDegPerDay, dDecDegPerDay} at epochJd. */
    private static double[] fitRobustLinearSkyTrack(List<Obs> obs, double epochJd) {
        double[] weights = new double[obs.size()];
        for (int i = 0; i < obs.size(); i++) {
            Obs o = obs.get(i);
            weights[i] = 1.0 / (o.sigmaRaArcsec() * o.sigmaRaArcsec() + o.sigmaDecArcsec() * o.sigmaDecArcsec());
        }
        double[] fit = fitLinearSkyTrack(obs, epochJd, weights);
        if (obs.size() < 4) return fit;
        // Two Huber IRLS passes so a single 2" outlier frame does not bias the night anchor or single-night velocity.
        double cosDec = Math.max(0.05, Math.cos(Math.toRadians(fit[1])));
        for (int pass = 0; pass < 2; pass++) {
            double[] errs = new double[obs.size()];
            for (int i = 0; i < obs.size(); i++) {
                Obs o = obs.get(i);
                double dt = o.jdTt() - epochJd;
                double dRa = SkyMath.normalizeSignedDegrees(o.raDeg() - (fit[0] + fit[2] * dt)) * cosDec * 3600.0;
                double dDec = (o.decDeg() - (fit[1] + fit[3] * dt)) * 3600.0;
                errs[i] = Math.hypot(dRa, dDec);
            }
            double[] sorted = errs.clone();
            Arrays.sort(sorted);
            double scale = Math.max(0.35, sorted[sorted.length / 2] * 1.4826);
            double huberK = 1.5 * scale;
            double[] wNext = new double[obs.size()];
            for (int i = 0; i < obs.size(); i++) {
                Obs o = obs.get(i);
                double base = 1.0 / (o.sigmaRaArcsec() * o.sigmaRaArcsec() + o.sigmaDecArcsec() * o.sigmaDecArcsec());
                double h = errs[i] <= huberK ? 1.0 : huberK / errs[i];
                wNext[i] = base * h;
            }
            fit = fitLinearSkyTrack(obs, epochJd, wNext);
        }
        return fit;
    }

    /** Weighted linear sky fit {ra0Deg, dec0Deg, dRaDegPerDay, dDecDegPerDay} at epochJd. */
    private static double[] fitLinearSkyTrack(List<Obs> obs, double epochJd, double[] weights) {
        double raRef = obs.get(obs.size() / 2).raDeg();
        double s1 = 0, st = 0, stt = 0, sr = 0, str = 0, sd = 0, std = 0;
        for (int i = 0; i < obs.size(); i++) {
            Obs o = obs.get(i);
            double dt = o.jdTt() - epochJd;
            double dRa = SkyMath.normalizeSignedDegrees(o.raDeg() - raRef);
            double w = weights[i];
            s1 += w;
            st += w * dt;
            stt += w * dt * dt;
            sr += w * dRa;
            str += w * dt * dRa;
            sd += w * o.decDeg();
            std += w * dt * o.decDeg();
        }
        double det = s1 * stt - st * st;
        if (Math.abs(det) < 1e-24) {
            return new double[]{raRef, obs.get(obs.size() / 2).decDeg(), 0.0, 0.0};
        }
        double ra0 = SkyMath.normalizeDegrees(raRef + (stt * sr - st * str) / det);
        double dRa = (s1 * str - st * sr) / det;
        double dec0 = (stt * sd - st * std) / det;
        double dDec = (s1 * std - st * sd) / det;
        return new double[]{ra0, dec0, dRa, dDec};
    }

    private static Obs pickMiddleObservation(List<Obs> obs) {
        double midTime = 0.5 * (obs.getFirst().jdTt() + obs.getLast().jdTt());
        Obs best = obs.get(obs.size() / 2);
        double bestDist = Double.POSITIVE_INFINITY;
        for (int k = 1; k < obs.size() - 1; k++) {
            double d = Math.abs(obs.get(k).jdTt() - midTime);
            if (d < bestDist) {
                bestDist = d;
                best = obs.get(k);
            }
        }
        return best;
    }

    // =========================================================================
    // Linear Algebra & Vector Helpers
    // =========================================================================

    private static double[] solveLinear(double[][] aIn, double[] bIn) {
        int n = bIn.length;
        double[][] a = new double[n][n];
        double[] b = bIn.clone();
        for (int i = 0; i < n; i++) System.arraycopy(aIn[i], 0, a[i], 0, n);
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double maxVal = Math.abs(a[col][col]);
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > maxVal) {
                    maxVal = Math.abs(a[r][col]);
                    pivot = r;
                }
            }
            if (maxVal < 1e-24 || !Double.isFinite(maxVal)) return null;
            if (pivot != col) {
                double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;
                double tb = b[col]; b[col] = b[pivot]; b[pivot] = tb;
            }
            double diag = a[col][col];
            for (int r = col + 1; r < n; r++) {
                double factor = a[r][col] / diag;
                for (int c = col; c < n; c++) a[r][c] -= factor * a[col][c];
                b[r] -= factor * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = b[r];
            for (int c = r + 1; c < n; c++) sum -= a[r][c] * x[c];
            x[r] = sum / a[r][r];
            if (!Double.isFinite(x[r])) return null;
        }
        return x;
    }

    private static double[][] invertSymmetric(double[][] mat) {
        int n = mat.length;
        double[][] inv = new double[n][n];
        for (int col = 0; col < n; col++) {
            double[] e = new double[n];
            e[col] = 1.0;
            double[] x = solveLinear(mat, e);
            if (x == null) return null;
            for (int r = 0; r < n; r++) inv[r][col] = x[r];
        }
        return inv;
    }

    private static double[][] cholesky(double[][] mat) {
        int n = mat.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = mat[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                if (i == j) {
                    if (sum <= 0.0 || !Double.isFinite(sum)) return null;
                    l[i][j] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    private static State toState(double[] x) {
        return new State(new double[]{x[0], x[1], x[2]}, new double[]{x[3], x[4], x[5]});
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) && v >= 0.0 ? v : null;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static double[] addScaled(double[] a, double[] b, double s) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + b[i] * s;
        return out;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double norm(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
