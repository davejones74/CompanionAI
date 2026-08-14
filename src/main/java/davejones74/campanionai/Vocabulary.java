package davejones74.campanionai;

import java.util.*;

public class Vocabulary {
    private Map<String, Integer> wordToIdx = new HashMap<>();
    private Map<Integer, String> idxToWord = new HashMap<>();

    public void build(String text) {
        String[] tokens = text.split("\\s+");
        int index = 0;
        for (String token : tokens) {
            if (!wordToIdx.containsKey(token) && !token.isEmpty()) {
                wordToIdx.put(token, index);
                idxToWord.put(index, token);
                index++;
            }
        }
    }

    public int getIdx(String word) { return wordToIdx.getOrDefault(word, 0); }
    public String getWord(int idx) { return idxToWord.getOrDefault(idx, "hello"); }
    public int size() { return wordToIdx.size(); }
}