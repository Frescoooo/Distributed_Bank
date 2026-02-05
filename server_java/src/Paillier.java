import java.math.BigInteger;
import java.security.SecureRandom;

public class Paillier {
    private static final BigInteger ONE = BigInteger.ONE;

    private final BigInteger n;
    private final BigInteger nSquared;
    private final BigInteger g;
    private final BigInteger lambda;
    private final BigInteger mu;
    private final SecureRandom random;

    public Paillier(int bitLength) {
        if (bitLength < 128) {
            throw new IllegalArgumentException("bitLength must be at least 128");
        }
        this.random = new SecureRandom();
        BigInteger p = BigInteger.probablePrime(bitLength / 2, random);
        BigInteger q;
        do {
            q = BigInteger.probablePrime(bitLength / 2, random);
        } while (p.equals(q));
        this.n = p.multiply(q);
        this.nSquared = n.multiply(n);
        this.g = n.add(ONE);
        this.lambda = lcm(p.subtract(ONE), q.subtract(ONE));
        BigInteger lValue = lFunction(g.modPow(lambda, nSquared));
        this.mu = lValue.modInverse(n);
    }

    public BigInteger encrypt(BigInteger message) {
        if (message.signum() < 0 || message.compareTo(n) >= 0) {
            throw new IllegalArgumentException("Message must be in Z_n");
        }
        BigInteger r = randomCoprime();
        BigInteger c1 = g.modPow(message, nSquared);
        BigInteger c2 = r.modPow(n, nSquared);
        return c1.multiply(c2).mod(nSquared);
    }

    public BigInteger decrypt(BigInteger ciphertext) {
        BigInteger u = ciphertext.modPow(lambda, nSquared);
        BigInteger lValue = lFunction(u);
        return lValue.multiply(mu).mod(n);
    }

    public BigInteger addCiphertexts(BigInteger c1, BigInteger c2) {
        return c1.multiply(c2).mod(nSquared);
    }

    public BigInteger sumCiphertexts(Iterable<BigInteger> ciphertexts) {
        BigInteger result = encrypt(BigInteger.ZERO);
        for (BigInteger ciphertext : ciphertexts) {
            result = result.multiply(ciphertext).mod(nSquared);
        }
        return result;
    }

    public BigInteger getModulus() {
        return n;
    }

    private BigInteger randomCoprime() {
        BigInteger candidate;
        do {
            candidate = new BigInteger(n.bitLength(), random);
        } while (candidate.signum() == 0
                || candidate.compareTo(n) >= 0
                || !candidate.gcd(n).equals(ONE));
        return candidate;
    }

    private BigInteger lFunction(BigInteger value) {
        return value.subtract(ONE).divide(n);
    }

    private BigInteger lcm(BigInteger a, BigInteger b) {
        return a.multiply(b).divide(a.gcd(b));
    }
}
