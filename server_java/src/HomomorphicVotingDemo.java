import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomomorphicVotingDemo {
    private static final int KEY_SIZE_BITS = 2048;
    private static final int[] VOTES = {1, 0, 1, 1, 0, 1, 0, 1};

    public static void main(String[] args) {
        Paillier paillier = new Paillier(KEY_SIZE_BITS);
        List<BigInteger> ciphertexts = new ArrayList<>();
        for (int vote : VOTES) {
            ciphertexts.add(paillier.encrypt(BigInteger.valueOf(vote)));
        }

        BigInteger traditionalSum = BigInteger.ZERO;
        List<BigInteger> decryptedVotes = new ArrayList<>();
        for (BigInteger ciphertext : ciphertexts) {
            BigInteger vote = paillier.decrypt(ciphertext);
            decryptedVotes.add(vote);
            traditionalSum = traditionalSum.add(vote);
        }

        BigInteger aggregatedCiphertext = paillier.sumCiphertexts(ciphertexts);
        BigInteger homomorphicSum = paillier.decrypt(aggregatedCiphertext);

        System.out.println("Votes (plaintext): " + formatVotes(VOTES));
        System.out.println("Traditional counting (decrypt each vote): " + decryptedVotes + ", sum=" + traditionalSum);
        System.out.println("Homomorphic tally (decrypt once): sum=" + homomorphicSum);
        System.out.println("Homomorphic step: multiply ciphertexts to add votes without revealing individuals.");
    }

    private static String formatVotes(int[] votes) {
        return Arrays.toString(votes);
    }
}
