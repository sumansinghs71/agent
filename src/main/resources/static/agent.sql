



-- src/main/resources/schema.sql
CREATE TABLE IF NOT EXISTS chatbot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    model_type ENUM('AZURE_OPENAI', 'LLAMA') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chatbot_id BIGINT NOT NULL,
    source_type ENUM('REST_API', 'PYTHON_CODE', 'DOCUMENT') NOT NULL,
    config JSON NOT NULL,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id)
);

CREATE TABLE IF NOT EXISTS document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chatbot_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    status ENUM('UPLOADED', 'PROCESSING', 'INDEXED', 'FAILED') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id)
);

CREATE TABLE IF NOT EXISTS chat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chatbot_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    message TEXT NOT NULL,
    sender ENUM('USER', 'AI') NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES chat_session(session_id)
);


select * from document

SELECT * FROM chatbot;
select * from tool
select * from tool where chatbot_id=2;

SELECT * FROM tool WHERE chatbot_id = 2 AND func_name_key = 'getUserById';

-- Tool definition table
CREATE TABLE tool (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  chatbot_id BIGINT NOT NULL,
  func_name_key VARCHAR(255) NOT NULL,
  label VARCHAR(255),
  prompt TEXT,
  params JSON,
  function_type ENUM('SQL','REST','PYTHON','JAVASCRIPT') NOT NULL,
  data_source VARCHAR(255),
  sql_query TEXT,
  http_method VARCHAR(20),
  http_path VARCHAR(500),
  http_headers JSON,
  http_body TEXT,
  timeout INT DEFAULT 30000,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY unique_tool_per_chatbot (chatbot_id, func_name_key),
  FOREIGN KEY (chatbot_id) REFERENCES chatbot(id) ON DELETE CASCADE
);

-- Tool execution history table
CREATE TABLE tool_execution_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tool_id BIGINT NOT NULL,
  chatbot_id BIGINT NOT NULL,
  session_id VARCHAR(255),
  input_params JSON,
  output_result TEXT,
  status ENUM('SUCCESS','FAILED','TIMEOUT') NOT NULL,
  error_message TEXT,
  execution_time_ms INT,
  executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (tool_id) REFERENCES tool(id) ON DELETE CASCADE,
  FOREIGN KEY (chatbot_id) REFERENCES chatbot(id) ON DELETE CASCADE
);

-- Index for performance
CREATE INDEX idx_tool_chatbot ON tool(chatbot_id);
CREATE INDEX idx_tool_execution_chatbot ON tool_execution_log(chatbot_id, executed_at);
CREATE INDEX idx_tool_execution_session ON tool_execution_log(session_id, executed_at);



-- Add instruction columns to chatbot table
ALTER TABLE chatbot 
ADD COLUMN system_instruction TEXT,
ADD COLUMN user_instruction TEXT,
ADD COLUMN instruction_enabled BOOLEAN DEFAULT TRUE;

-- Or create separate table for better management
CREATE TABLE chatbot_instruction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chatbot_id BIGINT NOT NULL,
    instruction_type ENUM('SYSTEM', 'USER') NOT NULL,
    instruction_text TEXT NOT NULL,
    priority INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id) ON DELETE CASCADE,
    INDEX idx_chatbot_enabled (chatbot_id, enabled)
);


CREATE TABLE IF NOT EXISTS citation_log (
    id BIGINT PRIMARY KEY,
    chatbot_id BIGINT NOT NULL,
    session_id VARCHAR(255),
    query TEXT,
    response TEXT,
    citations_json TEXT, -- JSON array of citations used
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (chatbot_id) REFERENCES chatbot(id) ON DELETE CASCADE
);

CREATE INDEX idx_citation_chatbot ON citation_log(chatbot_id, created_at);


select * from chatbot;
select * from tool where chatbot_id=2 and function_type='PYTHON';

[{"required": true, "paramType": "number", "defaultValue": null, "paramNameKey": "years", "paramDescription": "Number of years"}]
[{"required": true, "paramType": "string", "defaultValue": null, "paramNameKey": "numbers", "paramDescription": "Comma-separated list of numbers (e.g., 10,20,30,40,50)"}]

SELECT employee_id, CONCAT(first_name, " ", last_name) as full_name, department, position, hire_date, DATEDIFF(CURDATE(), hire_date) as days_employed FROM employees WHERE YEAR(hire_date) = {{$year}} ORDER BY hire_date DESC
select * from tool;

ALTER TABLE tool_execution_log 
ADD COLUMN execution_id VARCHAR(255),
ADD COLUMN parent_tool_id VARCHAR(255),
ADD COLUMN call_depth INT DEFAULT 0,
ADD COLUMN execution_chain JSON,
ADD INDEX idx_execution_id (execution_id);



-- Add execution tracking columns to tool_execution_log table
ALTER TABLE tool_execution_log 
  MODIFY COLUMN execution_chain TEXT,
  ADD COLUMN total_tools_called INT DEFAULT 1,
  ADD COLUMN user_id VARCHAR(255);



-- Add indexes for performance
CREATE INDEX idx_execution_id 
ON tool_execution_log(execution_id);

CREATE INDEX idx_parent_tool_id 
ON tool_execution_log(parent_tool_id);

CREATE INDEX idx_tool_execution_chatbot_created 
ON tool_execution_log(chatbot_id, created_at DESC);

CREATE INDEX idx_tool_execution_user 
ON tool_execution_log(user_id, created_at DESC);

-- Add comments

ALTER TABLE tool_execution_log
  MODIFY COLUMN execution_id VARCHAR(255) COMMENT 'Unique ID for the entire execution chain',
  MODIFY COLUMN parent_tool_id VARCHAR(255) COMMENT 'ID of the tool that called this tool',
  MODIFY COLUMN call_depth INT DEFAULT 0 COMMENT 'Depth in the call chain (0 = root)',
  MODIFY COLUMN execution_chain JSON COMMENT 'JSON array of the full call chain',
  MODIFY COLUMN total_tools_called INT DEFAULT 1 COMMENT 'Total number of tools called in this execution',
  MODIFY COLUMN user_id VARCHAR(255) COMMENT 'User who initiated the execution';



