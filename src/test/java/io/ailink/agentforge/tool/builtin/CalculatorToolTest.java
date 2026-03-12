package io.ailink.agentforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
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

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private JsonNode createExpression(String expression) {
        return objectMapper.createObjectNode().put("expression", expression);
    }

    // ==================== Basic Arithmetic Tests ====================

    @Test
    void testBasicAddition() {
        JsonNode args = createExpression("2 + 3");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testBasicSubtraction() {
        JsonNode args = createExpression("10 - 4");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("6", result.content());
    }

    @Test
    void testBasicMultiplication() {
        JsonNode args = createExpression("6 * 7");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("42", result.content());
    }

    @Test
    void testBasicDivision() {
        JsonNode args = createExpression("20 / 4");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testOperatorPrecedence() {
        // Multiplication has higher precedence than addition
        // 2 + 3 * 4 = 2 + 12 = 14 (NOT 5 * 4 = 20)
        JsonNode args = createExpression("2 + 3 * 4");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("14", result.content());
    }

    @Test
    void testParenthesesOverridePrecedence() {
        // Parentheses force addition first
        // (2 + 3) * 4 = 5 * 4 = 20
        JsonNode args = createExpression("(2 + 3) * 4");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("20", result.content());
    }

    @Test
    void testComplexPrecedence() {
        // 3 * (2 + 4) - 5 = 3 * 6 - 5 = 18 - 5 = 13
        JsonNode args = createExpression("3 * (2 + 4) - 5");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("13", result.content());
    }

    // ==================== Math Functions Tests ====================

    @Test
    void testSqrt() {
        JsonNode args = createExpression("sqrt(16)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4", result.content());
    }

    @Test
    void testSqrtDecimal() {
        JsonNode args = createExpression("sqrt(2)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1.4142135623730951", result.content());
    }

    @Test
    void testPow() {
        JsonNode args = createExpression("pow(2, 10)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1024", result.content());
    }

    @Test
    void testPowDecimal() {
        JsonNode args = createExpression("pow(2, 0.5)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1.4142135623730951", result.content());
    }

    @Test
    void testPowNegativeExponent() {
        JsonNode args = createExpression("pow(2, -2)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("0.25", result.content());
    }

    @Test
    void testAbs() {
        JsonNode args = createExpression("abs(-5)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testCeil() {
        JsonNode args = createExpression("ceil(4.3)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testFloor() {
        JsonNode args = createExpression("floor(4.7)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4", result.content());
    }

    @Test
    void testRound() {
        JsonNode args = createExpression("round(4.5)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testLog() {
        // log(e) ≈ 1.0, formatted as "1" since it's an integer value
        JsonNode args = createExpression("log(2.718281828459045)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1", result.content());
    }

    @Test
    void testExp() {
        JsonNode args = createExpression("exp(1)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("2.718281828459045", result.content());
    }

    @Test
    void testMin() {
        JsonNode args = createExpression("min(5, 3)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("3", result.content());
    }

    @Test
    void testMax() {
        JsonNode args = createExpression("max(5, 3)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    // ==================== Trigonometric Functions Tests ====================

    @Test
    void testSinPiOver2() {
        // sin(pi/2) ≈ 1.0, formatted as "1" since it's an integer value
        JsonNode args = createExpression("sin(pi/2)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1", result.content());
    }

    @Test
    void testCosZero() {
        // cos(0) = 1.0, formatted as "1" since it's an integer value
        JsonNode args = createExpression("cos(0)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("1", result.content());
    }

    @Test
    void testTanPiOver4() {
        // tan(pi/4) ≈ 1.0 but with floating point precision issues
        JsonNode args = createExpression("tan(pi/4)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        double value = Double.parseDouble(result.content());
        assertEquals(1.0, value, 0.0001);
    }

    @Test
    void testSinLargeAngle() {
        // sin(2*pi) should be ~0 due to floating point precision, actual result is very small
        JsonNode args = createExpression("sin(2 * pi)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        // Accept either "0" or a very small number due to floating point precision
        double value = Double.parseDouble(result.content());
        assertTrue(Math.abs(value) < 0.0001);
    }

    // ==================== Constants Tests ====================

    @Test
    void testPi() {
        JsonNode args = createExpression("pi");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("3.141592653589793", result.content());
    }

    @Test
    void testE() {
        JsonNode args = createExpression("e");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("2.718281828459045", result.content());
    }

    // ==================== Complex Expressions Tests ====================

    @Test
    void testComplexExpressionPiAndSin() {
        // (pi * 2) + sin(pi/2) = 6.283185307179586 + 1.0 = 7.283185307179586
        JsonNode args = createExpression("(pi * 2) + sin(pi/2)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("7.283185307179586", result.content());
    }

    @Test
    void testComplexExpressionPowAndSqrt() {
        // pow(3, 2) + sqrt(16) = 9 + 4 = 13
        JsonNode args = createExpression("pow(3, 2) + sqrt(16)");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("13", result.content());
    }

    @Test
    void testNestedFunctions() {
        // sqrt(pow(4, 2)) = sqrt(16) = 4
        JsonNode args = createExpression("sqrt(pow(4, 2))");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4", result.content());
    }

    @Test
    void testComplexArithmetic() {
        // (10 + 5) * (3 - 1) / 2 = 15 * 2 / 2 = 15
        JsonNode args = createExpression("(10 + 5) * (3 - 1) / 2");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("15", result.content());
    }

    @Test
    void testMultipleOperations() {
        // 2 + 3 * 4 - 5 / 5 = 2 + 12 - 1 = 13
        JsonNode args = createExpression("2 + 3 * 4 - 5 / 5");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("13", result.content());
    }

    // ==================== Error Handling Tests ====================

    @Test
    void testNullArguments() {
        ToolResult result = calculatorTool.execute(null);
        assertTrue(result.isError());
        assertTrue(result.content().contains("缺少必需参数"));
    }

    @Test
    void testMissingExpression() {
        JsonNode args = objectMapper.createObjectNode();
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
        assertTrue(result.content().contains("缺少必需参数"));
    }

    @Test
    void testEmptyExpression() {
        JsonNode args = createExpression("");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
        assertTrue(result.content().contains("表达式不能为空"));
    }

    @Test
    void testBlankExpression() {
        JsonNode args = createExpression("   ");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
        assertTrue(result.content().contains("表达式不能为空"));
    }

    @Test
    void testInvalidCharacters() {
        JsonNode args = createExpression("2 + 3; rm -rf");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
        assertTrue(result.content().contains("不允许的字符"));
    }

    @Test
    void testInvalidExpression() {
        JsonNode args = createExpression("2 + + 3");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
        assertTrue(result.content().contains("计算错误"));
    }

    @Test
    void testDivisionByZero() {
        // Division by zero returns Infinity in Java, not an error
        JsonNode args = createExpression("1 / 0");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("Infinity", result.content());
    }

    @Test
    void testMismatchedParentheses() {
        JsonNode args = createExpression("(2 + 3");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
    }

    @Test
    void testUnknownFunction() {
        JsonNode args = createExpression("unknown(5)");
        ToolResult result = calculatorTool.execute(args);
        assertTrue(result.isError());
    }

    // ==================== Result Formatting Tests ====================

    @Test
    void testIntegerResultFormatting() {
        // 4.0 should be formatted as "4"
        JsonNode args = createExpression("2 + 2");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4", result.content());
    }

    @Test
    void testDecimalResultFormatting() {
        // 4.5 should be formatted as "4.5"
        JsonNode args = createExpression("9 / 2");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4.5", result.content());
    }

    @Test
    void testNegativeResultFormatting() {
        JsonNode args = createExpression("5 - 10");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("-5", result.content());
    }

    @Test
    void testZeroResultFormatting() {
        JsonNode args = createExpression("5 - 5");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("0", result.content());
    }

    // ==================== Edge Cases Tests ====================

    @Test
    void testSingleNumber() {
        JsonNode args = createExpression("42");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("42", result.content());
    }

    @Test
    void testNegativeNumber() {
        JsonNode args = createExpression("-5 + 3");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("-2", result.content());
    }

    @Test
    void testDecimalNumbers() {
        JsonNode args = createExpression("1.5 + 2.5");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("4", result.content());
    }

    @Test
    void testSpacesInExpression() {
        JsonNode args = createExpression("  2  +  3  ");
        ToolResult result = calculatorTool.execute(args);
        assertFalse(result.isError());
        assertEquals("5", result.content());
    }

    @Test
    void testToolName() {
        assertEquals("calculator", calculatorTool.name());
    }

    @Test
    void testToolDescription() {
        assertNotNull(calculatorTool.description());
        assertFalse(calculatorTool.description().isEmpty());
    }

    @Test
    void testInputSchema() {
        JsonNode schema = calculatorTool.inputSchema();
        assertNotNull(schema);
        assertTrue(schema.has("properties"));
    }
}
