-- ============================================================================
-- DATABASE SCHEMA UPDATES FOR PYTHON & JAVASCRIPT tool
-- ============================================================================
-- Run this on your MySQL database to add Python and JavaScript support
-- ============================================================================

USE chatbot_db;

-- ============================================================================
-- STEP 1: ALTER tool TABLE
-- ============================================================================

-- Add Python and JavaScript support to function_type enum
ALTER TABLE tool 
MODIFY COLUMN function_type ENUM('REST', 'SQL', 'PYTHON', 'JAVASCRIPT') NOT NULL;

-- Add Python-specific columns
ALTER TABLE tool 
ADD COLUMN python_code TEXT NULL COMMENT 'Python code to execute',
ADD COLUMN python_version VARCHAR(20) NULL COMMENT 'Python version (e.g., 3.x)',
ADD COLUMN allowed_modules JSON NULL COMMENT 'List of allowed Python modules';

-- Add JavaScript-specific columns
ALTER TABLE tool 
ADD COLUMN js_code TEXT NULL COMMENT 'JavaScript code to execute',
ADD COLUMN js_engine VARCHAR(20) NULL COMMENT 'JavaScript engine (nashorn/graalvm)';

-- Add execution metadata columns
ALTER TABLE tool
ADD COLUMN max_memory_mb INT DEFAULT 512 COMMENT 'Maximum memory in MB',
ADD COLUMN max_execution_time_ms INT DEFAULT 30000 COMMENT 'Max execution time in milliseconds',
ADD COLUMN sandbox_enabled BOOLEAN DEFAULT TRUE COMMENT 'Enable sandboxing for code execution';

-- ============================================================================
-- STEP 2: CREATE TOOL EXECUTION LOG TABLE (Optional but recommended)
-- ============================================================================

CREATE TABLE IF NOT EXISTS tool_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_id BIGINT NOT NULL,
    chatbot_id BIGINT NOT NULL,
    function_type ENUM('REST', 'SQL', 'PYTHON', 'JAVASCRIPT') NOT NULL,
    execution_status ENUM('SUCCESS', 'FAILED', 'TIMEOUT', 'SECURITY_VIOLATION') NOT NULL,
    execution_time_ms BIGINT NOT NULL,
    input_params JSON NULL,
    output_data JSON NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_tool_id (tool_id),
    INDEX idx_chatbot_id (chatbot_id),
    INDEX idx_status (execution_status),
    INDEX idx_created_at (created_at),
    
    FOREIGN KEY (tool_id) REFERENCES tool(id) ON DELETE CASCADE,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================
-- STEP 3: INSERT SAMPLE PYTHON tool FOR CHATBOT ID 2
-- ============================================================================

-- Python Tool 1: Calculate Statistics
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    python_code,
    python_version,
    allowed_modules,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'calculateStatistics',
    'Calculate Statistics',
    'Calculates statistical metrics (mean, median, std dev) for a list of numbers',
    '[{"paramNameKey":"numbers","paramDescription":"Comma-separated list of numbers (e.g., 10,20,30,40,50)","paramType":"string","required":true,"defaultValue":null}]',
    'PYTHON',
    '# Parse input numbers
import json
import math

# Convert comma-separated string to list of numbers
number_list = [float(x.strip()) for x in numbers.split(",")]

# Calculate statistics
n = len(number_list)
mean = sum(number_list) / n
variance = sum((x - mean) ** 2 for x in number_list) / n
std_dev = math.sqrt(variance)

# Sort for median
sorted_numbers = sorted(number_list)
if n % 2 == 0:
    median = (sorted_numbers[n//2 - 1] + sorted_numbers[n//2]) / 2
else:
    median = sorted_numbers[n//2]

# Calculate range
min_val = min(number_list)
max_val = max(number_list)

# Store result
result = {
    "count": n,
    "mean": round(mean, 2),
    "median": round(median, 2),
    "std_dev": round(std_dev, 2),
    "variance": round(variance, 2),
    "min": min_val,
    "max": max_val,
    "range": max_val - min_val
}',
    '3.x',
    '["json", "math"]',
    30000,
    NOW(),
    NOW()
);

-- Python Tool 2: Date Calculator
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    python_code,
    python_version,
    allowed_modules,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'calculateDateDifference',
    'Calculate Date Difference',
    'Calculates the number of days between two dates',
    '[{"paramNameKey":"startDate","paramDescription":"Start date in YYYY-MM-DD format","paramType":"string","required":true,"defaultValue":null},{"paramNameKey":"endDate","paramDescription":"End date in YYYY-MM-DD format","paramType":"string","required":true,"defaultValue":null}]',
    'PYTHON',
    'import datetime
import json

# Parse dates
start = datetime.datetime.strptime(startDate, "%Y-%m-%d")
end = datetime.datetime.strptime(endDate, "%Y-%m-%d")

# Calculate difference
delta = end - start
days_diff = abs(delta.days)

# Calculate weeks, months (approximate)
weeks = days_diff // 7
months = days_diff // 30

# Calculate business days (excluding weekends)
business_days = 0
current = start
while current <= end if delta.days >= 0 else current >= end:
    if current.weekday() < 5:  # Monday = 0, Sunday = 6
        business_days += 1
    current += datetime.timedelta(days=1 if delta.days >= 0 else -1)

result = {
    "start_date": startDate,
    "end_date": endDate,
    "days": days_diff,
    "weeks": weeks,
    "approximate_months": months,
    "business_days": business_days,
    "direction": "forward" if delta.days >= 0 else "backward"
}',
    '3.x',
    '["datetime", "json"]',
    30000,
    NOW(),
    NOW()
);

-- Python Tool 3: Text Analyzer
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    python_code,
    python_version,
    allowed_modules,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'analyzeText',
    'Analyze Text',
    'Analyzes text and provides word count, character count, and sentence count',
    '[{"paramNameKey":"text","paramDescription":"Text to analyze","paramType":"string","required":true,"defaultValue":null}]',
    'PYTHON',
    'import re
import json

# Count characters
char_count = len(text)
char_no_spaces = len(text.replace(" ", ""))

# Count words
words = text.split()
word_count = len(words)

# Count sentences (simple approximation)
sentences = re.split(r"[.!?]+", text)
sentence_count = len([s for s in sentences if s.strip()])

# Count paragraphs
paragraphs = text.split("\n\n")
paragraph_count = len([p for p in paragraphs if p.strip()])

# Average word length
avg_word_length = sum(len(word) for word in words) / word_count if word_count > 0 else 0

# Find longest word
longest_word = max(words, key=len) if words else ""

result = {
    "character_count": char_count,
    "character_count_no_spaces": char_no_spaces,
    "word_count": word_count,
    "sentence_count": sentence_count,
    "paragraph_count": paragraph_count,
    "average_word_length": round(avg_word_length, 2),
    "longest_word": longest_word,
    "longest_word_length": len(longest_word)
}',
    '3.x',
    '["re", "json"]',
    30000,
    NOW(),
    NOW()
);

-- ============================================================================
-- STEP 4: INSERT SAMPLE JAVASCRIPT tool FOR CHATBOT ID 2
-- ============================================================================

-- JavaScript Tool 1: Calculate Compound Interest
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    js_code,
    js_engine,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'calculateCompoundInterest',
    'Calculate Compound Interest',
    'Calculates compound interest based on principal, rate, time, and frequency',
    '[{"paramNameKey":"principal","paramDescription":"Principal amount (e.g., 1000)","paramType":"number","required":true,"defaultValue":null},{"paramNameKey":"rate","paramDescription":"Annual interest rate as percentage (e.g., 5 for 5%)","paramType":"number","required":true,"defaultValue":null},{"paramNameKey":"years","paramDescription":"Time period in years","paramType":"number","required":true,"defaultValue":null},{"paramNameKey":"frequency","paramDescription":"Compounding frequency per year (1=annually, 4=quarterly, 12=monthly)","paramType":"number","required":true,"defaultValue":"12"}]',
    'JAVASCRIPT',
    '// Calculate compound interest
// Formula: A = P(1 + r/n)^(nt)
// Where:
//   A = final amount
//   P = principal
//   r = annual rate (as decimal)
//   n = number of times interest is compounded per year
//   t = time in years

var rateDecimal = rate / 100;
var amount = principal * Math.pow((1 + rateDecimal / frequency), frequency * years);
var interest = amount - principal;

var result = {
    principal: principal,
    rate: rate + "%",
    years: years,
    compounding_frequency: frequency,
    final_amount: Math.round(amount * 100) / 100,
    total_interest: Math.round(interest * 100) / 100,
    effective_rate: Math.round(((amount / principal - 1) / years) * 10000) / 100 + "%"
};

result;',
    'nashorn',
    30000,
    NOW(),
    NOW()
);

-- JavaScript Tool 2: JSON Validator and Formatter
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    js_code,
    js_engine,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'validateJSON',
    'Validate and Format JSON',
    'Validates JSON syntax and provides formatted output',
    '[{"paramNameKey":"jsonString","paramDescription":"JSON string to validate","paramType":"string","required":true,"defaultValue":null}]',
    'JAVASCRIPT',
    '// Try to parse and validate JSON
var result = {};

try {
    var parsed = JSON.parse(jsonString);
    result.valid = true;
    result.formatted = JSON.stringify(parsed, null, 2);
    result.error = null;
    
    // Analyze structure
    result.type = Array.isArray(parsed) ? "array" : typeof parsed;
    
    if (typeof parsed === "object" && parsed !== null) {
        result.keys = Object.keys(parsed);
        result.key_count = result.keys.length;
    }
    
    if (Array.isArray(parsed)) {
        result.length = parsed.length;
    }
    
} catch (e) {
    result.valid = false;
    result.error = e.message;
    result.formatted = null;
}

result;',
    'nashorn',
    30000,
    NOW(),
    NOW()
);

-- JavaScript Tool 3: Temperature Converter
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    js_code,
    js_engine,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'convertTemperature',
    'Convert Temperature',
    'Converts temperature between Celsius, Fahrenheit, and Kelvin',
    '[{"paramNameKey":"value","paramDescription":"Temperature value","paramType":"number","required":true,"defaultValue":null},{"paramNameKey":"fromUnit","paramDescription":"Source unit (C, F, or K)","paramType":"string","required":true,"defaultValue":null}]',
    'JAVASCRIPT',
    '// Convert temperature to all units
var celsius, fahrenheit, kelvin;

fromUnit = fromUnit.toUpperCase();

// Convert to Celsius first
if (fromUnit === "C") {
    celsius = value;
} else if (fromUnit === "F") {
    celsius = (value - 32) * 5/9;
} else if (fromUnit === "K") {
    celsius = value - 273.15;
} else {
    throw new Error("Invalid unit. Use C, F, or K");
}

// Convert from Celsius to others
fahrenheit = (celsius * 9/5) + 32;
kelvin = celsius + 273.15;

// Round to 2 decimal places
celsius = Math.round(celsius * 100) / 100;
fahrenheit = Math.round(fahrenheit * 100) / 100;
kelvin = Math.round(kelvin * 100) / 100;

var result = {
    input: value + "°" + fromUnit,
    celsius: celsius + "°C",
    fahrenheit: fahrenheit + "°F",
    kelvin: kelvin + "K",
    conversions: {
        "Celsius": celsius,
        "Fahrenheit": fahrenheit,
        "Kelvin": kelvin
    }
};

result;',
    'nashorn',
    30000,
    NOW(),
    NOW()
);

-- ============================================================================
-- STEP 5: VERIFICATION QUERIES
-- ============================================================================

-- Verify Python tool
SELECT 
    id,
    func_name_key,
    label,
    function_type,
    SUBSTRING(python_code, 1, 50) as code_preview
FROM tool 
WHERE chatbot_id = 2 AND function_type = 'PYTHON';

-- Verify JavaScript tool
SELECT 
    id,
    func_name_key,
    label,
    function_type,
    SUBSTRING(js_code, 1, 50) as code_preview
FROM tool 
WHERE chatbot_id = 2 AND function_type = 'JAVASCRIPT';

-- Count tool by type for chatbot 2
SELECT 
    function_type,
    COUNT(*) as tool_count
FROM tool 
WHERE chatbot_id = 2
GROUP BY function_type;

-- ============================================================================
-- SETUP COMPLETE!
-- ============================================================================
-- Chatbot ID 2 now has:
-- ✓ 6 SQL tool
-- ✓ 3 Python tool (statistics, date calc, text analysis)
-- ✓ 3 JavaScript tool (compound interest, JSON validator, temperature)
-- ✓ 4+ REST API tool (from original import)
-- 
-- Total: 16+ tool across 4 types!
-- ============================================================================
