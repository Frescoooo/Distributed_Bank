import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class HomomorphicVotingDemo {
    private static final int[] VOTES = {1, 0, 1, 1, 0, 1, 0, 1};

    public static void main(String[] args) {
        Paillier paillier = new Paillier(2048);
        List<BigInteger> ciphertexts = new ArrayList<>();
        for (int vote : VOTES) {
            ciphertexts.add(paillier.encrypt(BigInteger.valueOf(vote)));
        }

        int traditionalSum = 0;
        List<Integer> decryptedVotes = new ArrayList<>();
        for (BigInteger ciphertext : ciphertexts) {
            int vote = paillier.decrypt(ciphertext).intValue();
            decryptedVotes.add(vote);
            traditionalSum += vote;
        }

        BigInteger aggregatedCiphertext = paillier.sumCiphertexts(ciphertexts);
        BigInteger homomorphicSum = paillier.decrypt(aggregatedCiphertext);

        System.out.println("Votes (plaintext): " + formatVotes(VOTES));
        System.out.println("Traditional counting (decrypt each vote): " + decryptedVotes + ", sum=" + traditionalSum);
        System.out.println("Homomorphic tally (decrypt once): sum=" + homomorphicSum);
        System.out.println("Homomorphic step: multiply ciphertexts to add votes without revealing individuals.");
    }

    private static String formatVotes(int[] votes) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < votes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(votes[i]);
        }
        builder.append("]");
        return builder.toString();
    }
}
