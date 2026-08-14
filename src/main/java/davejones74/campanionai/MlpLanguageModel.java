package davejones74.campanionai;

import java.util.Random;

public class MlpLanguageModel {
    private final int vocabSize;
    private final int hiddenSize;
    private final int contextWindow;

    private double[][] weights1; // Shape: [contextWindow * vocabSize][hiddenSize]
    private double[][] weights2; // Shape: [hiddenSize][vocabSize]

    private final double learningRate = 0.05;
    private final Random rand = new Random();

    public MlpLanguageModel(int vocabSize, int hiddenSize, int contextWindow) {
        this.vocabSize = vocabSize;
        this.hiddenSize = hiddenSize;
        this.contextWindow = contextWindow;

        this.weights1 = new double[contextWindow * vocabSize][hiddenSize];
        this.weights2 = new double[hiddenSize][vocabSize];

        initializeWeights();
    }

    private void initializeWeights() {
        int fanIn1 = contextWindow * vocabSize;
        double scale1 = Math.sqrt(2.0 / fanIn1);
        double scale2 = Math.sqrt(2.0 / hiddenSize);

        for (int i = 0; i < weights1.length; i++) {
            for (int j = 0; j < hiddenSize; j++) {
                weights1[i][j] = rand.nextGaussian() * scale1;
            }
        }
        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < vocabSize; j++) {
                weights2[i][j] = rand.nextGaussian() * scale2;
            }
        }
    }

    private double relu(double x) {
        return Math.max(0.0, x);
    }

    private double reluDerivative(double x) {
        return x > 0.0 ? 1.0 : 0.0;
    }

    private double[] softmax(double[] logits) {
        double[] probs = new double[vocabSize];
        double max = logits[0];
        for (double v : logits) if (v > max) max = v;

        double sum = 0.0;
        for (int i = 0; i < vocabSize; i++) {
            probs[i] = Math.exp(logits[i] - max);
            sum += probs[i];
        }
        for (int i = 0; i < vocabSize; i++) {
            probs[i] /= sum;
        }
        return probs;
    }

    // Forward pass over a context window (the last N token indices).
    public MlpForwardResult forward(int[] contextIndices) {
        double[] hiddenInputs = new double[hiddenSize];
        for (int p = 0; p < contextIndices.length; p++) {
            int row = p * vocabSize + contextIndices[p];
            for (int i = 0; i < hiddenSize; i++) {
                hiddenInputs[i] += weights1[row][i];
            }
        }

        double[] hiddenOutputs = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            hiddenOutputs[i] = relu(hiddenInputs[i]);
        }

        double[] logits = new double[vocabSize];
        for (int j = 0; j < vocabSize; j++) {
            double sum = 0.0;
            for (int i = 0; i < hiddenSize; i++) {
                sum += hiddenOutputs[i] * weights2[i][j];
            }
            logits[j] = sum;
        }

        double[] probabilities = softmax(logits);
        return new MlpForwardResult(contextIndices, hiddenInputs, hiddenOutputs, logits, probabilities);
    }

    public void train(int[] contextIndices, int targetWordIdx) {
        MlpForwardResult result = forward(contextIndices);

        double[] outputGradients = new double[vocabSize];
        for (int j = 0; j < vocabSize; j++) {
            double target = (j == targetWordIdx) ? 1.0 : 0.0;
            outputGradients[j] = result.probabilities[j] - target;
        }

        double[] hiddenGradients = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double errorSignal = 0.0;
            for (int j = 0; j < vocabSize; j++) {
                errorSignal += outputGradients[j] * weights2[i][j];
            }
            hiddenGradients[i] = errorSignal * reluDerivative(result.hiddenInputs[i]);
        }

        for (int i = 0; i < hiddenSize; i++) {
            for (int j = 0; j < vocabSize; j++) {
                weights2[i][j] -= learningRate * outputGradients[j] * result.hiddenOutputs[i];
            }
        }

        int[] context = result.contextIndices;
        for (int p = 0; p < context.length; p++) {
            int row = p * vocabSize + context[p];
            for (int i = 0; i < hiddenSize; i++) {
                weights1[row][i] -= learningRate * hiddenGradients[i];
            }
        }
    }

    // Sample the next token index from the temperature-scaled distribution.
    public int sampleIndex(int[] contextIndices, double temperature) {
        MlpForwardResult result = forward(contextIndices);
        double[] logits = result.logits;

        double max = logits[0];
        for (double v : logits) if (v > max) max = v;

        double invT = 1.0 / temperature;
        double[] scaled = new double[vocabSize];
        double sum = 0.0;
        for (int j = 0; j < vocabSize; j++) {
            scaled[j] = Math.exp((logits[j] - max) * invT);
            sum += scaled[j];
        }

        double r = rand.nextDouble() * sum;
        for (int j = 0; j < vocabSize; j++) {
            r -= scaled[j];
            if (r <= 0.0) {
                return j;
            }
        }
        return vocabSize - 1;
    }
}

class MlpForwardResult {
    int[] contextIndices;
    double[] hiddenInputs;
    double[] hiddenOutputs;
    double[] logits;
    double[] probabilities;

    MlpForwardResult(int[] ci, double[] hi, double[] ho, double[] lg, double[] p) {
        this.contextIndices = ci;
        this.hiddenInputs = hi;
        this.hiddenOutputs = ho;
        this.logits = lg;
        this.probabilities = p;
    }
}