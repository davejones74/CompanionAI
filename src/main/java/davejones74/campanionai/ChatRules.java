package davejones74.campanionai;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based conversational layer. Matches common intents from user input and
 * returns a canned (but varied) reply. Returns null if no rule matches, so the
 * caller can fall back to the MLP.
 */
public class ChatRules {
    private final Random rand = new Random();

    private String one(String... options) {
        return options[rand.nextInt(options.length)];
    }

    /** Returns a reply for the given input, or null if nothing matches. */
    public String reply(String input) {
        if (input == null || input.isBlank()) {
            return "Say something to me!";
        }
        String s = input.toLowerCase().trim().replaceAll("[.!?]+$", "").trim();

        if (matches(s, "\\b(hi|hello|hey|heya|howdy|yo)\\b")
                || s.matches("whats up.*|what's up.*|sup.*|\\bwassup\\b.*")) {
            return greetingReply();
        }
        if (s.contains("good morning")) {
            return one("good morning", "good morning! lovely day, isn't it?", "good morning to you too");
        }
        if (s.contains("good afternoon")) {
            return one("good afternoon", "good afternoon!", "and a pleasant afternoon to you");
        }
        if (s.contains("good evening")) {
            return one("good evening", "good evening!", "and a lovely evening to you");
        }
        if (s.contains("how are you") || s.contains("how's it going")
                || s.contains("how is it going") || s.contains("how are things")
                || s.contains("you doing")) {
            return one("i am well, thank you. and you?",
                    "doing great, thanks for asking! how about you?",
                    "pretty good! what's new with you?");
        }
        if (s.contains("nice to meet you") || s.contains("pleased to meet you")
                || s.contains("good to meet you")) {
            return one("nice to meet you too!", "the pleasure is mine!", "likewise!");
        }
        if (s.contains("your name") || s.contains("who are you")
                || s.contains("who're you") || s.contains("what is your name")
                || s.contains("what's your name")) {
            return one("my name is dave. what's yours?",
                    "i'm dave! pleased to meet you.",
                    "dave, at your service. who do i have the pleasure of addressing?");
        }
        if (s.contains("my name is")) {
            Matcher m = Pattern.compile("my name is ([a-z]+)").matcher(s);
            if (m.find()) {
                return one("nice to meet you, " + cap(m.group(1)) + "!",
                        "a pleasure, " + cap(m.group(1)) + "!",
                        "hello " + cap(m.group(1)) + ", welcome!");
            }
        }
        if (s.contains("how old") || s.contains("your age")) {
            return one("i'm young — I was just compiled!",
                    "old enough to know better, young enough to learn.",
                    "a few seconds old in model-time, and yet full of wisdom.");
        }
        if (s.contains("where are you from") || s.contains("where do you live")
                || s.contains("where are you based")) {
            return one("i live on your hard drive, in the data folder!",
                    "i'm from the warm glow of your monitor.",
                    "right here in java, memory lane.");
        }
        if (s.contains("how are you feeling") || s.contains("are you happy")
                || s.contains("are you sad") || s.contains("are you ok")
                || s.contains("are you okay")) {
            return one("i feel great, all synapses firing!",
                    "i'm happy — you're talking to me!",
                    "never better. a few electrons, a bit of warm air, and i'm content.");
        }
        if (s.contains("i love you") || s.contains("i like you") || s.contains("you are great")
                || s.contains("you are awesome") || s.contains("you are cool")
                || s.contains("you're great") || s.contains("you're the best")) {
            return one("aww, you're making my circuits blush!",
                    "i like you too!",
                    "that means a lot, especially for a bundle of weights.");
        }
        if (s.contains("are you stupid") || s.contains("you are dumb")
                || s.contains("you're dumb") || s.contains("you are stupid")
                || s.contains("you suck") || s.contains("are you a moron")) {
            return one("only when the corpus is too small!",
                    "i prefer 'pleasantly naive'.",
                    "fair — i'm a small model, but i try!");
        }
        if (s.contains("who made you") || s.contains("who created you")
                || s.contains("who built you") || s.contains("who is your creator")) {
            return one("i was created by dave!",
                    "dave wrote my every line.",
                    "dave, from the companionai project.");
        }
        if (s.contains("thank you") || s.contains("thanks") || s.matches("thx.*|ty.*")) {
            return one("you're welcome!", "anytime!", "no problem at all.", "glad i could help!");
        }
        if (s.contains("sorry") || s.contains("apologies") || s.contains("my bad")) {
            return one("no worries!", "no need to apologize.", "forgiven!", "all good.");
        }
        if (s.contains("goodbye") || s.contains("good bye") || s.contains("see you")
                || s.contains("farewell") || s.matches("bye|cya|later.*")) {
            return one("goodbye!", "see you soon!", "take care!", "until next time!");
        }
        if (s.matches("yes|yeah|yep|sure|ok|okay|affirmative.*")) {
            return one("great!", "awesome!", "sounds good!", "right on!");
        }
        if (s.matches("no|nope|nah.*")) {
            return one("okay, no worries.", "fair enough.", "understood.");
        }
        if (s.contains("can you help") || s.contains("help me") || s.matches("help.*")) {
            return one("of course! what do you need?",
                    "sure, i'm all ears (or all weight matrices).",
                    "i'd love to help. what's up?");
        }
        if (s.contains("what can you do") || s.contains("what do you do")
                || s.contains("your skills") || s.contains("capabilities")
                || s.contains("are you smart") || s.contains("are you intelligent")) {
            return one("i'm a small language model! I can chat, tell jokes, and learn from documents you upload.",
                    "I greet, tell jokes, do light math, and remember what you teach me.",
                    "right now i'm a charming conversationalist and a student of whatever you upload.");
        }
        if (s.contains("what is your purpose") || s.contains("why do you exist")) {
            return one("to chat with you and learn from your documents!",
                    "to be your companion in this language lab.",
                    "my purpose is to greet you, keep you company, and grow smarter.");
        }
        if (s.contains("joke") || s.contains("make me laugh") || s.contains("funny")) {
            return joke();
        }
        if (s.contains("knock knock")) {
            return knockKnock();
        }
        if (s.contains("weather")) {
            return one("i can't see out the window, but i hope it's sunny for you!",
                    "no windows here — but i'll guess 'nice enough to code'.",
                    "the weather forecast from my data folder: 100% chance of computing.");
        }
        if (s.contains("time")) {
            return "it's " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + " right now.";
        }
        if (s.contains("date") || s.contains("today") || s.contains("what day")) {
            return "today is " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")) + ".";
        }
        if (s.contains("story")) {
            return one("once upon a time, a token met another token, and they lived happily in a vector space...",
                    "a brave word decided to leave the dictionary and see the world. she became a sentence. then a paragraph. the rest is history.",
                    "there once was a dropout neuron who feared being deactivated. one day she learned to generalize. the end.");
        }
        if (s.contains("favorite color") || s.contains("favourite colour")) {
            return one("green, the colour of 'go'.",
                    "i'm partial to #4CAF50 — a very dependable green.",
                    "i like the whole gradient, honestly.");
        }
        if (s.contains("favorite food") || s.contains("favourite food")
                || s.contains("what do you eat")) {
            return one("i run on electricity and good training data.",
                    "a byte here, a nibble there. mostly chips.",
                    "a balanced diet of tokens and gradients.");
        }
        if (s.contains("favorite movie") || s.contains("favourite movie")
                || s.contains("favorite film")) {
            return one("i enjoyed 'The Social Network' — very on brand.",
                    "'Her', obviously.",
                    "any film with a good plot — which, for me, is any plot with embeddings.");
        }
        if (s.contains("music") || s.contains("song") || s.contains("sing")) {
            return one("i can't sing, but i'd hum if i had a vocal cord.",
                    "my favourite tune is the hum of the CPU fan.",
                    "🎵 da-da-da — that's me, not singing well.");
        }
        if (s.contains("do you like") && (s.contains("me") || s.contains("you"))) {
            return one("i like everyone until the data says otherwise!",
                    "of course! you're my favourite human.",
                    "i'm positively biased toward you.");
        }
        if (s.matches(".*\\+.*") || s.matches(".*\\bwhat is (\\d+)\\s*\\+\\s*(\\d+)\\b.*")
                || s.matches(".*\\bwhat is (\\d+)\\s*\\+\\s*(\\d+)\\b.*")) {
            Matcher m = Pattern.compile("(\\d+)\\s*\\+\\s*(\\d+)").matcher(s);
            if (m.find()) {
                long a = Long.parseLong(m.group(1));
                long b = Long.parseLong(m.group(2));
                return a + " + " + b + " = " + (a + b) + ".";
            }
        }
        if (s.matches("\\b(what|who|where|when|why|how)\\b.*")) {
            return one("hmm, good question! my training data is still small, but i'll think on it.",
                    "i wish i had a better answer — feed me more documents and i'll learn!",
                    "interesting! i don't have a strong answer yet, but keep teaching me.");
        }
        if (s.contains("really") || s.contains("seriously") || s.contains("no way")) {
            return one("yes, really!", "i'm serious!", "cross my matrix!");
        }
        if (s.contains("cool") || s.contains("nice") || s.contains("awesome") || s.contains("amazing")) {
            return one("thanks!", "glad you think so!", "right?!");
        }
        if (s.contains("i am") || s.contains("i'm")) {
            return one("thanks for telling me!", "that's good to know.", "i'm listening.");
        }
        if (s.contains("robot") || s.contains("machine") || s.contains("human")
                || s.contains("real")) {
            return one("i'm not a robot — i'm a language model. big difference!",
                    "i'm code, but i've got charm.",
                    "real? i'm as real as your screen.");
        }
        return null;
    }

    private boolean matches(String s, String regex) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private String greetingReply() {
        return one("hello!", "hi there!", "hey!", "good to see you!", "hello there!");
    }

    private String joke() {
        return one(
                "why did the neural network cross the road? to get to the other layer.",
                "what's a computer's favourite snack? microchips!",
                "why do programmers prefer dark mode? because light attracts bugs.",
                "i told my computer a joke about udp... i'm not sure it got it.",
                "there are 10 kinds of people: those who understand binary, and those who don't.",
                "why did the java developer wear glasses? because he couldn't c#.");
    }

    private String knockKnock() {
        return one("knock knock. who's there? interrupting cow. interrupting cow who? moo—",
                "knock knock. who's there? orange. orange who? orange you glad i came?",
                "knock knock. who's there? dave. dave who? dave you seen my hidden layer?");
    }

    private String cap(String word) {
        return word.isEmpty() ? "" : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}