package davejones74.campanionai;

import java.util.*;

public class Vocabulary {
    private Map<String, Integer> wordToIdx = new HashMap<>();
    private Map<Integer, String> idxToWord = new HashMap<>();

    public void build(String text) {
        if (!wordToIdx.containsKey("<unk>")) {
            wordToIdx.put("<unk>", 0);
            idxToWord.put(0, "<unk>");
        }
        int index = wordToIdx.size();
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            if (!token.isEmpty() && !wordToIdx.containsKey(token)) {
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