-- Create database
CREATE DATABASE IF NOT EXISTS chatbot_db
CHARACTER SET utf8mb4
COLLATE utf8mb4_0900_ai_ci;

USE chatbot_db;

-- Agent table
CREATE TABLE IF NOT EXISTS agent (
id BIGINT AUTO_INCREMENT PRIMARY KEY,
name VARCHAR(255) NOT NULL UNIQUE,
type ENUM('AZURE_OPENAI', 'OLLAMA') NOT NULL,
model_name VARCHAR(255) NOT NULL,
api_key VARCHAR(255),
resource_name VARCHAR(255),
base_url VARCHAR(255),
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ChatSession table
CREATE TABLE IF NOT EXISTS chat_session (
id BIGINT AUTO_INCREMENT PRIMARY KEY,
session_id VARCHAR(36) NOT NULL UNIQUE,
agent_id BIGINT NOT NULL,
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
INDEX idx_session_id (session_id),
FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ChatMessage table
CREATE TABLE IF NOT EXISTS chat_message (
id BIGINT AUTO_INCREMENT PRIMARY KEY,
session_id BIGINT NOT NULL,
content TEXT NOT NULL,
is_user_message BOOLEAN NOT NULL,
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
INDEX idx_session_created (session_id, created_at),
FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB;


-- Add composite index for message retrieval
ALTER TABLE chat_message ADD INDEX idx_session_user_message (session_id, is_user_message);

-- Add fulltext index for message content (optional)
ALTER TABLE chat_message ADD FULLTEXT idx_content (content);




-- Insert sample agents
INSERT INTO agent (name, type, model_name, api_key, resource_name, base_url) VALUES
('azure-gpt4', 'AZURE_OPENAI', 'gpt-4', 'your-azure-key', 'your-resource', NULL),
('local-llama3', 'OLLAMA', 'llama3', NULL, NULL, 'http://localhost:11434');

-- Insert sample session
INSERT INTO chat_session (session_id, agent_id) VALUES
('session-12345', (SELECT id FROM agent WHERE name = 'local-llama3'));

-- Insert sample messages
INSERT INTO chat_message (session_id, content, is_user_message) VALUES
(1, 'Hello, how are you?', 1),
(1, 'I''m doing well, thank you! How can I assist you today?', 0);


SELECT s.session_id, a.name, COUNT(m.id) AS message_count
FROM chat_session s
JOIN agent a ON s.agent_id = a.id
JOIN chat_message m ON s.id = m.session_id
GROUP BY s.id;



select * from agent;





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