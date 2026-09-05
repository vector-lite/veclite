package veclite.math;

import java.util.Locale;

/**
 * 安全的轻量级得分计算表达式求值器。
 * 仅支持基础数学四则运算、幂运算、括号、常用数学函数以及 `score` 变量，
 * 物理杜绝任意外部代码执行（RCE）或反射调用风险。
 */
public final class ScoreExpressionEvaluator {

    @FunctionalInterface
    public interface ScoreFunction {
        float evaluate(float score);
    }

    private static final ScoreFunction IDENTITY = score -> score;

    private ScoreExpressionEvaluator() {}

    /**
     * 编译表达式为高性能求值函数。
     * 若表达式为 null 或空字符串，返回恒等函数（原样返回 score）。
     *
     * @param expression 类似 "score * 2.0 - 1.0"、"(score + 1) / 2" 的数学表达式
     * @return 编译后的 ScoreFunction
     * @throws IllegalArgumentException 表达式语法错误时抛出
     */
    public static ScoreFunction compile(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return IDENTITY;
        }
        return new Parser(expression.trim()).parse();
    }

    private static class Parser {
        private final String text;
        private int pos = 0;

        Parser(String text) {
            this.text = text;
        }

        ScoreFunction parse() {
            ScoreFunction result = parseExpression();
            skipWhitespace();
            if (pos < text.length()) {
                throw new IllegalArgumentException("Unexpected character '" + text.charAt(pos) + "' in score expression: " + text);
            }
            return result;
        }

        private ScoreFunction parseExpression() {
            ScoreFunction node = parseTerm();
            while (true) {
                skipWhitespace();
                if (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == '+') {
                        pos++;
                        ScoreFunction right = parseTerm();
                        ScoreFunction left = node;
                        node = score -> left.evaluate(score) + right.evaluate(score);
                        continue;
                    } else if (c == '-') {
                        pos++;
                        ScoreFunction right = parseTerm();
                        ScoreFunction left = node;
                        node = score -> left.evaluate(score) - right.evaluate(score);
                        continue;
                    }
                }
                break;
            }
            return node;
        }

        private ScoreFunction parseTerm() {
            ScoreFunction node = parseFactor();
            while (true) {
                skipWhitespace();
                if (pos < text.length()) {
                    char c = text.charAt(pos);
                    if (c == '*') {
                        pos++;
                        ScoreFunction right = parseFactor();
                        ScoreFunction left = node;
                        node = score -> left.evaluate(score) * right.evaluate(score);
                        continue;
                    } else if (c == '/') {
                        pos++;
                        ScoreFunction right = parseFactor();
                        ScoreFunction left = node;
                        node = score -> left.evaluate(score) / right.evaluate(score);
                        continue;
                    } else if (c == '%') {
                        pos++;
                        ScoreFunction right = parseFactor();
                        ScoreFunction left = node;
                        node = score -> left.evaluate(score) % right.evaluate(score);
                        continue;
                    }
                }
                break;
            }
            return node;
        }

        private ScoreFunction parseFactor() {
            skipWhitespace();
            if (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '+') {
                    pos++;
                    return parseFactor();
                } else if (c == '-') {
                    pos++;
                    ScoreFunction operand = parseFactor();
                    return score -> -operand.evaluate(score);
                }
            }
            return parsePower();
        }

        private ScoreFunction parsePower() {
            ScoreFunction left = parsePrimary();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '^') {
                pos++;
                ScoreFunction right = parseFactor();
                return score -> (float) Math.pow(left.evaluate(score), right.evaluate(score));
            }
            return left;
        }

        private ScoreFunction parsePrimary() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of expression: " + text);
            }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                ScoreFunction node = parseExpression();
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis in expression: " + text);
                }
                pos++;
                return node;
            }

            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }

            if (Character.isLetter(c) || c == '_') {
                return parseIdentifier();
            }

            throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos + " in expression: " + text);
        }

        private ScoreFunction parseNumber() {
            int start = pos;
            boolean hasDot = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !hasDot) {
                    hasDot = true;
                    pos++;
                } else if ((c == 'e' || c == 'E') && pos > start) {
                    pos++;
                    if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                        pos++;
                    }
                } else {
                    break;
                }
            }
            String numStr = text.substring(start, pos);
            try {
                float val = Float.parseFloat(numStr);
                return score -> val;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number '" + numStr + "' in expression: " + text);
            }
        }

        private ScoreFunction parseIdentifier() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            String name = text.substring(start, pos);
            String lower = name.toLowerCase(Locale.ROOT);

            if ("score".equals(lower)) {
                return score -> score;
            }

            // 支持内置常用数学函数
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '(') {
                pos++;
                ScoreFunction arg1 = parseExpression();
                skipWhitespace();
                if ("sqrt".equals(lower)) {
                    expectClosingParen();
                    return score -> (float) Math.sqrt(arg1.evaluate(score));
                } else if ("abs".equals(lower)) {
                    expectClosingParen();
                    return score -> Math.abs(arg1.evaluate(score));
                } else if ("exp".equals(lower)) {
                    expectClosingParen();
                    return score -> (float) Math.exp(arg1.evaluate(score));
                } else if ("log".equals(lower)) {
                    expectClosingParen();
                    return score -> (float) Math.log(arg1.evaluate(score));
                } else if ("min".equals(lower)) {
                    expectComma();
                    ScoreFunction arg2 = parseExpression();
                    expectClosingParen();
                    return score -> Math.min(arg1.evaluate(score), arg2.evaluate(score));
                } else if ("max".equals(lower)) {
                    expectComma();
                    ScoreFunction arg2 = parseExpression();
                    expectClosingParen();
                    return score -> Math.max(arg1.evaluate(score), arg2.evaluate(score));
                } else {
                    throw new IllegalArgumentException("Unsupported function '" + name + "' in expression: " + text);
                }
            }

            throw new IllegalArgumentException("Unknown identifier '" + name + "' in expression: " + text);
        }

        private void expectClosingParen() {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != ')') {
                throw new IllegalArgumentException("Missing closing parenthesis in expression: " + text);
            }
            pos++;
        }

        private void expectComma() {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != ',') {
                throw new IllegalArgumentException("Expected comma in expression: " + text);
            }
            pos++;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
