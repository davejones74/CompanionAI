package davejones74.campanionai;

import java.util.Random;

public class MlpLanguageModel {
    private int vocabSize;
    private int hiddenSize;
    
    private double[][] weights1; // Shape: [vocabSize][hiddenSize]
    private double[][] weights2; // Shape: [hiddenSize][vocabSize]
    
    private double learningRate = 0.05;

    public MlpLanguageModel(int vocabSize, int hiddenSize) {
        this.vocabSize = vocabSize;
        this.hiddenSize = hiddenSize;
        
        this.weights1 = new double[vocabSize][hiddenSize];
        this.weights2 = new double[hiddenSize][vocabSize];
        
        initializeWeights();
    }

    private void initializeWeights() {
        Random rand = new Random();
        double scale1 = Math.sqrt(2.0 / vocabSize);
        double scale2 = Math.sqrt(2.0 / hiddenSize);

        for (int i = 0; i < vocabSize; i++) {
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

    private double[] softmax(double[] raw) {
        double[] probs = new double[vocabSize];
        double max = raw[0];
        for (double v : raw) if (v > max) max = v;
        
        double sum = 0.0;
        for (int i = 0; i < vocabSize; i++) {
            probs[i] = Math.exp(raw[i] - max);
            sum += probs[i];
        }
        for (int i = 0; i < vocabSize; i++) {
            probs[i] /= sum;
        }
        return probs;
    }

    public MlpForwardResult forward(int inputWordIdx) {
        double[] hiddenInputs = weights1[inputWordIdx];
        double[] hiddenOutputs = new double[hiddenSize];
        
        for (int i = 0; i < hiddenSize; i++) {
            hiddenOutputs[i] = relu(hiddenInputs[i]);
        }

        double[] outputLogits = new double[vocabSize];
        for (int j = 0; j < vocabSize; j++) {
            for (int i = 0; i < hiddenSize; i++) {
                outputLogits[j] += hiddenOutputs[i] * weights2[i][j];
            }
        }

        double[] probabilities = softmax(outputLogits);
        return new MlpForwardResult(hiddenInputs, hiddenOutputs, probabilities);
    }

    public void train(int inputWordIdx, int targetWordIdx) {
        MlpForwardResult result = forward(inputWordIdx);
        
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

        for (int i = 0; i < hiddenSize; i++) {
            weights1[inputWordIdx][i] -= learningRate * hiddenGradients[i];
        }
    }
}

class MlpForwardResult {
    double[] hiddenInputs;
    double[] hiddenOutputs;
    double[] probabilities;

    MlpForwardResult(double[] hi, double[] ho, double[] p) {
        this.hiddenInputs = hi;
        this.hiddenOutputs = ho;
        this.probabilities = p;
    }
}