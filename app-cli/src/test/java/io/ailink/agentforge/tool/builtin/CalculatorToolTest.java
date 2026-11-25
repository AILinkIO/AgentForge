package io.ailink.agentforge.tool.builtin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for CalculatorTool.
 * Tests all features: basic arithmetic, math functions, trig functions,
 * constants, complex expressions, error handling, and result formatting.
 */
@SpringBootTest
class CalculatorToolTest {

    @Autowired
    private CalculatorTool calculatorTool;

    // ==================== Basic Arithmetic Tests ====================

    @Test
    void testBasicAddition() {
        String result = calculatorTool.calculate("2 + 3");
        assertEquals("5", result);
    }

    @Test
    void testBasicSubtraction() {
        String result = calculatorTool.calculate("10 - 4");
        assertEquals("6", result);
    }

    @Test
    void testBasicMultiplication() {
        String result = calculatorTool.calculate("6 * 7");
        assertEquals("42", result);
    }

    @Test
    void testBasicDivision() {
        String result = calculatorTool.calculate("20 / 4");
        assertEquals("5", result);
    }

    @Test
    void testOperatorPrecedence() {
        // Multiplication has higher precedence than addition
        // 2 + 3 * 4 = 2 + 12 = 14 (NOT 5 * 4 = 20)
        String result = calculatorTool.calculate("2 + 3 * 4");
        assertEquals("14", result);
    }

    @Test
    void testParenthesesOverridePrecedence() {
        // Parentheses force addition first
        // (2 + 3) * 4 = 5 * 4 = 20
        String result = calculatorTool.calculate("(2 + 3) * 4");
        assertEquals("20", result);
    }

    @Test
    void testComplexPrecedence() {
        // 3 * (2 + 4) - 5 = 3 * 6 - 5 = 18 - 5 = 13
        String result = calculatorTool.calculate("3 * (2 + 4) - 5");
        assertEquals("13", result);
    }

    // ==================== Math Functions Tests ====================

    @Test
    void testSqrt() {
        String result = calculatorTool.calculate("sqrt(16)");
        assertEquals("4", result);
    }

    @Test
    void testSqrtDecimal() {
        String result = calculatorTool.calculate("sqrt(2)");
        assertEquals("1.4142135623730951", result);
    }

    @Test
    void testPow() {
        String result = calculatorTool.calculate("pow(2, 10)");
        assertEquals("1024", result);
    }

    @Test
    void testPowDecimal() {
        String result = calculatorTool.calculate("pow(2, 0.5)");
        assertEquals("1.4142135623730951", result);
    }

    @Test
    void testPowNegativeExponent() {
        String result = calculatorTool.calculate("pow(2, -2)");
        assertEquals("0.25", result);
    }

    @Test
    void testAbs() {
        String result = calculatorTool.calculate("abs(-5)");
        assertEquals("5", result);
    }

    @Test
    void testCeil() {
        String result = calculatorTool.calculate("ceil(4.3)");
        assertEquals("5", result);
    }

    @Test
    void testFloor() {
        String result = calculatorTool.calculate("floor(4.7)");
        assertEquals("4", result);
    }

    @Test
    void testRound() {
        String result = calculatorTool.calculate("round(4.5)");
        assertEquals("5", result);
    }

    @Test
    void testLog() {
        // log(e) ≈ 1.0, formatted as "1" since it's an integer value
        String result = calculatorTool.calculate("log(2.718281828459045)");
        assertEquals("1", result);
    }

    @Test
    void testExp() {
        String result = calculatorTool.calculate("exp(1)");
        assertEquals("2.718281828459045", result);
    }

    @Test
    void testMin() {
        String result = calculatorTool.calculate("min(5, 3)");
        assertEquals("3", result);
    }

    @Test
    void testMax() {
        String result = calculatorTool.calculate("max(5, 3)");
        assertEquals("5", result);
    }

    // ==================== Trigonometric Functions Tests ====================

    @Test
    void testSinPiOver2() {
        // sin(pi/2) ≈ 1.0, formatted as "1" since it's an integer value
        String result = calculatorTool.calculate("sin(pi/2)");
        assertEquals("1", result);
    }

    @Test
    void testCosZero() {
        // cos(0) = 1.0, formatted as "1" since it's an integer value
        String result = calculatorTool.calculate("cos(0)");
        assertEquals("1", result);
    }

    @Test
    void testTanPiOver4() {
        // tan(pi/4) ≈ 1.0 but with floating point precision issues
        String result = calculatorTool.calculate("tan(pi/4)");
        double value = Double.parseDouble(result);
        assertEquals(1.0, value, 0.0001);
    }

    @Test
    void testSinLargeAngle() {
        // sin(2*pi) should be ~0 due to floating point precision, actual result is very small
        String result = calculatorTool.calculate("sin(2 * pi)");
        // Accept either "0" or a very small number due to floating point precision
        double value = Double.parseDouble(result);
        assertTrue(Math.abs(value) < 0.0001);
    }

    // ==================== Constants Tests ====================

    @Test
    void testPi() {
        String result = calculatorTool.calculate("pi");
        assertEquals("3.141592653589793", result);
    }

    @Test
    void testE() {
        String result = calculatorTool.calculate("e");
        assertEquals("2.718281828459045", result);
    }

    // ==================== Complex Expressions Tests ====================

    @Test
    void testComplexExpressionPiAndSin() {
        // (pi * 2) + sin(pi/2) = 6.283185307179586 + 1.0 = 7.283185307179586
        String result = calculatorTool.calculate("(pi * 2) + sin(pi/2)");
        assertEquals("7.283185307179586", result);
    }

    @Test
    void testComplexExpressionPowAndSqrt() {
        // pow(3, 2) + sqrt(16) = 9 + 4 = 13
        String result = calculatorTool.calculate("pow(3, 2) + sqrt(16)");
        assertEquals("13", result);
    }

    @Test
    void testNestedFunctions() {
        // sqrt(pow(4, 2)) = sqrt(16) = 4
        String result = calculatorTool.calculate("sqrt(pow(4, 2))");
        assertEquals("4", result);
    }

    @Test
    void testComplexArithmetic() {
        // (10 + 5) * (3 - 1) / 2 = 15 * 2 / 2 = 15
        String result = calculatorTool.calculate("(10 + 5) * (3 - 1) / 2");
        assertEquals("15", result);
    }

    @Test
    void testMultipleOperations() {
        // 2 + 3 * 4 - 5 / 5 = 2 + 12 - 1 = 13
        String result = calculatorTool.calculate("2 + 3 * 4 - 5 / 5");
        assertEquals("13", result);
    }

    // ==================== Error Handling Tests ====================

    @Test
    void testNullExpression() {
        String result = calculatorTool.calculate(null);
        assertTrue(result.contains("表达式不能为空"));
    }

    @Test
    void testEmptyExpression() {
        String result = calculatorTool.calculate("");
        assertTrue(result.contains("表达式不能为空"));
    }

    @Test
    void testBlankExpression() {
        String result = calculatorTool.calculate("   ");
        assertTrue(result.contains("表达式不能为空"));
    }

    @Test
    void testInvalidCharacters() {
        String result = calculatorTool.calculate("2 + 3; rm -rf");
        assertTrue(result.contains("不允许的字符"));
    }

    @Test
    void testInvalidExpression() {
        String result = calculatorTool.calculate("2 + + 3");
        assertTrue(result.contains("计算错误"));
    }

    @Test
    void testDivisionByZero() {
        // Division by zero returns Infinity in Java, not an error
        String result = calculatorTool.calculate("1 / 0");
        assertEquals("Infinity", result);
    }

    @Test
    void testMismatchedParentheses() {
        String result = calculatorTool.calculate("(2 + 3");
        assertTrue(result.contains("计算错误"));
    }

    @Test
    void testUnknownFunction() {
        String result = calculatorTool.calculate("unknown(5)");
        assertTrue(result.contains("计算错误"));
    }

    // ==================== Result Formatting Tests ====================

    @Test
    void testIntegerResultFormatting() {
        // 4.0 should be formatted as "4"
        String result = calculatorTool.calculate("2 + 2");
        assertEquals("4", result);
    }

    @Test
    void testDecimalResultFormatting() {
        // 4.5 should be formatted as "4.5"
        String result = calculatorTool.calculate("9 / 2");
        assertEquals("4.5", result);
    }

    @Test
    void testNegativeResultFormatting() {
        String result = calculatorTool.calculate("5 - 10");
        assertEquals("-5", result);
    }

    @Test
    void testZeroResultFormatting() {
        String result = calculatorTool.calculate("5 - 5");
        assertEquals("0", result);
    }

    // ==================== Edge Cases Tests ====================

    @Test
    void testSingleNumber() {
        String result = calculatorTool.calculate("42");
        assertEquals("42", result);
    }

    @Test
    void testNegativeNumber() {
        String result = calculatorTool.calculate("-5 + 3");
        assertEquals("-2", result);
    }

    @Test
    void testDecimalNumbers() {
        String result = calculatorTool.calculate("1.5 + 2.5");
        assertEquals("4", result);
    }

    @Test
    void testSpacesInExpression() {
        String result = calculatorTool.calculate("  2  +  3  ");
        assertEquals("5", result);
    }
}
