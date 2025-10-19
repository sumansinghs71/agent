



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