-- ============================================================================
-- SQL TOOL TEST SETUP FOR CHATBOT ID 2
-- ============================================================================
-- This script creates test tables, inserts sample data, and configures SQL tools
-- Run this on your MySQL database (chatbot_db)
-- ============================================================================

USE chatbot_db;

-- ============================================================================
-- STEP 1: CREATE TEST TABLES
-- ============================================================================

-- Create employees table for testing
CREATE TABLE IF NOT EXISTS employees (
    id INT AUTO_INCREMENT PRIMARY KEY,
    employee_id VARCHAR(20) UNIQUE NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    phone VARCHAR(20),
    department VARCHAR(50) NOT NULL,
    position VARCHAR(100) NOT NULL,
    salary DECIMAL(10, 2) NOT NULL,
    hire_date DATE NOT NULL,
    status ENUM('ACTIVE', 'ON_LEAVE', 'TERMINATED') DEFAULT 'ACTIVE',
    manager_id INT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_department (department),
    INDEX idx_status (status),
    INDEX idx_manager (manager_id)
) ENGINE=InnoDB;

-- Create departments table
CREATE TABLE IF NOT EXISTS departments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    dept_code VARCHAR(10) UNIQUE NOT NULL,
    dept_name VARCHAR(100) NOT NULL,
    location VARCHAR(100),
    budget DECIMAL(12, 2),
    head_employee_id INT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Create projects table
CREATE TABLE IF NOT EXISTS projects (
    id INT AUTO_INCREMENT PRIMARY KEY,
    project_code VARCHAR(20) UNIQUE NOT NULL,
    project_name VARCHAR(200) NOT NULL,
    department VARCHAR(50) NOT NULL,
    budget DECIMAL(12, 2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    status ENUM('PLANNING', 'IN_PROGRESS', 'COMPLETED', 'ON_HOLD') DEFAULT 'PLANNING',
    priority ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL') DEFAULT 'MEDIUM',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ============================================================================
-- STEP 2: INSERT TEST DATA
-- ============================================================================

-- Insert departments
INSERT INTO departments (dept_code, dept_name, location, budget) VALUES
('ENG', 'Engineering', 'San Francisco', 5000000.00),
('MKT', 'Marketing', 'New York', 2000000.00),
('SAL', 'Sales', 'Chicago', 3000000.00),
('HR', 'Human Resources', 'Austin', 800000.00),
('FIN', 'Finance', 'New York', 1500000.00),
('OPS', 'Operations', 'Seattle', 2500000.00),
('IT', 'Information Technology', 'San Francisco', 4000000.00),
('SUP', 'Customer Support', 'Remote', 1200000.00);

-- Insert employees (including managers)
INSERT INTO employees (employee_id, first_name, last_name, email, phone, department, position, salary, hire_date, status, manager_id) VALUES
-- Engineering Team
('EMP001', 'John', 'Smith', 'john.smith@company.com', '555-0101', 'Engineering', 'VP Engineering', 180000.00, '2020-01-15', 'ACTIVE', NULL),
('EMP002', 'Sarah', 'Johnson', 'sarah.johnson@company.com', '555-0102', 'Engineering', 'Senior Software Engineer', 145000.00, '2020-03-20', 'ACTIVE', 1),
('EMP003', 'Michael', 'Williams', 'michael.williams@company.com', '555-0103', 'Engineering', 'Software Engineer', 120000.00, '2021-06-10', 'ACTIVE', 1),
('EMP004', 'Emily', 'Brown', 'emily.brown@company.com', '555-0104', 'Engineering', 'Junior Software Engineer', 95000.00, '2022-09-01', 'ACTIVE', 2),
('EMP005', 'David', 'Jones', 'david.jones@company.com', '555-0105', 'Engineering', 'DevOps Engineer', 135000.00, '2021-02-15', 'ACTIVE', 1),

-- Marketing Team
('EMP006', 'Jessica', 'Davis', 'jessica.davis@company.com', '555-0106', 'Marketing', 'Marketing Director', 150000.00, '2019-11-01', 'ACTIVE', NULL),
('EMP007', 'Robert', 'Miller', 'robert.miller@company.com', '555-0107', 'Marketing', 'Marketing Manager', 110000.00, '2020-07-15', 'ACTIVE', 6),
('EMP008', 'Jennifer', 'Wilson', 'jennifer.wilson@company.com', '555-0108', 'Marketing', 'Content Specialist', 85000.00, '2021-11-20', 'ACTIVE', 7),
('EMP009', 'William', 'Moore', 'william.moore@company.com', '555-0109', 'Marketing', 'Social Media Manager', 90000.00, '2022-03-01', 'ACTIVE', 7),

-- Sales Team
('EMP010', 'Mary', 'Taylor', 'mary.taylor@company.com', '555-0110', 'Sales', 'Sales Director', 160000.00, '2019-08-01', 'ACTIVE', NULL),
('EMP011', 'James', 'Anderson', 'james.anderson@company.com', '555-0111', 'Sales', 'Senior Sales Rep', 120000.00, '2020-10-15', 'ACTIVE', 10),
('EMP012', 'Patricia', 'Thomas', 'patricia.thomas@company.com', '555-0112', 'Sales', 'Sales Rep', 95000.00, '2021-12-01', 'ACTIVE', 10),
('EMP013', 'Christopher', 'Jackson', 'christopher.jackson@company.com', '555-0113', 'Sales', 'Sales Rep', 92000.00, '2022-05-10', 'ACTIVE', 11),

-- HR Team
('EMP014', 'Linda', 'White', 'linda.white@company.com', '555-0114', 'Human Resources', 'HR Director', 140000.00, '2019-05-01', 'ACTIVE', NULL),
('EMP015', 'Daniel', 'Harris', 'daniel.harris@company.com', '555-0115', 'Human Resources', 'HR Manager', 105000.00, '2020-09-15', 'ACTIVE', 14),
('EMP016', 'Barbara', 'Martin', 'barbara.martin@company.com', '555-0116', 'Human Resources', 'HR Specialist', 80000.00, '2021-07-01', 'ACTIVE', 15),

-- Finance Team
('EMP017', 'Richard', 'Thompson', 'richard.thompson@company.com', '555-0117', 'Finance', 'CFO', 200000.00, '2019-01-01', 'ACTIVE', NULL),
('EMP018', 'Susan', 'Garcia', 'susan.garcia@company.com', '555-0118', 'Finance', 'Financial Analyst', 100000.00, '2020-11-01', 'ACTIVE', 17),
('EMP019', 'Joseph', 'Martinez', 'joseph.martinez@company.com', '555-0119', 'Finance', 'Accountant', 85000.00, '2021-08-15', 'ACTIVE', 17),

-- IT Team
('EMP020', 'Thomas', 'Robinson', 'thomas.robinson@company.com', '555-0120', 'Information Technology', 'IT Director', 155000.00, '2019-10-01', 'ACTIVE', NULL),
('EMP021', 'Nancy', 'Clark', 'nancy.clark@company.com', '555-0121', 'Information Technology', 'System Administrator', 110000.00, '2020-12-15', 'ACTIVE', 20),
('EMP022', 'Charles', 'Rodriguez', 'charles.rodriguez@company.com', '555-0122', 'Information Technology', 'Network Engineer', 105000.00, '2021-04-01', 'ACTIVE', 20),

-- Operations Team
('EMP023', 'Margaret', 'Lewis', 'margaret.lewis@company.com', '555-0123', 'Operations', 'Operations Director', 145000.00, '2019-12-01', 'ACTIVE', NULL),
('EMP024', 'Paul', 'Lee', 'paul.lee@company.com', '555-0124', 'Operations', 'Operations Manager', 115000.00, '2020-06-15', 'ACTIVE', 23),
('EMP025', 'Betty', 'Walker', 'betty.walker@company.com', '555-0125', 'Operations', 'Logistics Coordinator', 75000.00, '2022-01-10', 'ACTIVE', 24),

-- Support Team
('EMP026', 'Mark', 'Hall', 'mark.hall@company.com', '555-0126', 'Customer Support', 'Support Manager', 100000.00, '2020-04-01', 'ACTIVE', NULL),
('EMP027', 'Dorothy', 'Allen', 'dorothy.allen@company.com', '555-0127', 'Customer Support', 'Senior Support Specialist', 70000.00, '2021-03-15', 'ACTIVE', 26),
('EMP028', 'Steven', 'Young', 'steven.young@company.com', '555-0128', 'Customer Support', 'Support Specialist', 60000.00, '2022-07-01', 'ACTIVE', 26),

-- Some employees on leave
('EMP029', 'Helen', 'King', 'helen.king@company.com', '555-0129', 'Marketing', 'Marketing Analyst', 88000.00, '2021-05-20', 'ON_LEAVE', 7),
('EMP030', 'George', 'Wright', 'george.wright@company.com', '555-0130', 'Sales', 'Sales Rep', 90000.00, '2020-08-10', 'ON_LEAVE', 11);

-- Insert projects
INSERT INTO projects (project_code, project_name, department, budget, start_date, end_date, status, priority) VALUES
('PROJ001', 'AI Chatbot Platform Development', 'Engineering', 800000.00, '2024-01-01', '2024-12-31', 'IN_PROGRESS', 'HIGH'),
('PROJ002', 'Marketing Campaign Q4 2024', 'Marketing', 250000.00, '2024-10-01', '2024-12-31', 'IN_PROGRESS', 'MEDIUM'),
('PROJ003', 'CRM System Upgrade', 'Information Technology', 450000.00, '2024-06-01', '2025-03-31', 'IN_PROGRESS', 'HIGH'),
('PROJ004', 'Customer Support Portal', 'Customer Support', 300000.00, '2024-08-01', '2025-02-28', 'IN_PROGRESS', 'MEDIUM'),
('PROJ005', 'Financial Reporting Automation', 'Finance', 200000.00, '2024-09-01', '2025-01-31', 'IN_PROGRESS', 'MEDIUM'),
('PROJ006', 'Mobile App Development', 'Engineering', 1000000.00, '2024-03-01', '2025-06-30', 'IN_PROGRESS', 'CRITICAL'),
('PROJ007', 'HR Management System', 'Human Resources', 350000.00, '2024-07-01', '2025-04-30', 'PLANNING', 'LOW'),
('PROJ008', 'Supply Chain Optimization', 'Operations', 600000.00, '2024-05-01', '2025-05-31', 'IN_PROGRESS', 'HIGH'),
('PROJ009', 'Website Redesign', 'Marketing', 180000.00, '2024-11-01', '2025-01-31', 'PLANNING', 'MEDIUM'),
('PROJ010', 'Data Analytics Platform', 'Engineering', 900000.00, '2024-02-01', '2025-08-31', 'IN_PROGRESS', 'CRITICAL');

-- ============================================================================
-- STEP 3: INSERT SQL TOOLS FOR CHATBOT ID 2
-- ============================================================================

-- Tool 1: Get all employees by department
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getEmployeesByDepartment',
    'Get Employees By Department',
    'Retrieves all employees working in a specific department',
    '[{"paramNameKey":"department","paramDescription":"Department name (e.g., Engineering, Marketing, Sales, HR, Finance, IT, Operations, Customer Support)","paramType":"string","required":true,"defaultValue":null}]',
    'SQL',
    'SELECT employee_id, CONCAT(first_name, " ", last_name) as full_name, email, position, salary, hire_date, status FROM employees WHERE department = "{{$department}}" ORDER BY salary DESC',
    30000,
    NOW(),
    NOW()
);

-- Tool 2: Get employee details by ID
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getEmployeeById',
    'Get Employee Details',
    'Retrieves detailed information about a specific employee by their employee ID',
    '[{"paramNameKey":"employeeId","paramDescription":"Employee ID (e.g., EMP001, EMP002)","paramType":"string","required":true,"defaultValue":null}]',
    'SQL',
    'SELECT e.*, CONCAT(m.first_name, " ", m.last_name) as manager_name FROM employees e LEFT JOIN employees m ON e.manager_id = m.id WHERE e.employee_id = "{{$employeeId}}"',
    30000,
    NOW(),
    NOW()
);

-- Tool 3: Get department statistics
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getDepartmentStats',
    'Get Department Statistics',
    'Retrieves statistics for a department including employee count, average salary, and total payroll',
    '[{"paramNameKey":"department","paramDescription":"Department name","paramType":"string","required":true,"defaultValue":null}]',
    'SQL',
    'SELECT department, COUNT(*) as employee_count, AVG(salary) as avg_salary, SUM(salary) as total_payroll, MIN(salary) as min_salary, MAX(salary) as max_salary FROM employees WHERE department = "{{$department}}" AND status = "ACTIVE" GROUP BY department',
    30000,
    NOW(),
    NOW()
);

-- Tool 4: Get all active projects
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getActiveProjects',
    'Get Active Projects',
    'Retrieves all active projects, optionally filtered by department',
    '[{"paramNameKey":"department","paramDescription":"Department name (optional, leave empty for all departments)","paramType":"string","required":false,"defaultValue":""}]',
    'SQL',
    'SELECT project_code, project_name, department, budget, start_date, end_date, status, priority FROM projects WHERE status IN ("PLANNING", "IN_PROGRESS") {{#department}}AND department = "{{department}}"{{/department}} ORDER BY priority DESC, start_date ASC',
    30000,
    NOW(),
    NOW()
);

-- Tool 5: Get high earners
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getHighEarners',
    'Get High Earners',
    'Retrieves employees earning above a specified salary threshold',
    '[{"paramNameKey":"minSalary","paramDescription":"Minimum salary threshold (e.g., 100000)","paramType":"integer","required":true,"defaultValue":"100000"}]',
    'SQL',
    'SELECT employee_id, CONCAT(first_name, " ", last_name) as full_name, department, position, salary FROM employees WHERE salary >= {{$minSalary}} AND status = "ACTIVE" ORDER BY salary DESC LIMIT 20',
    30000,
    NOW(),
    NOW()
);

-- Tool 6: Get employees hired in a date range
INSERT INTO tool (
    chatbot_id,
    func_name_key,
    label,
    prompt,
    params,
    function_type,
    sql_query,
    timeout,
    created_at,
    updated_at
) VALUES (
    2,
    'getEmployeesByHireDate',
    'Get Employees By Hire Date',
    'Retrieves employees hired within a specific year',
    '[{"paramNameKey":"year","paramDescription":"Hire year (e.g., 2020, 2021, 2022)","paramType":"integer","required":true,"defaultValue":"2024"}]',
    'SQL',
    'SELECT employee_id, CONCAT(first_name, " ", last_name) as full_name, department, position, hire_date, DATEDIFF(CURDATE(), hire_date) as days_employed FROM employees WHERE YEAR(hire_date) = {{$year}} ORDER BY hire_date DESC',
    30000,
    NOW(),
    NOW()
);

-- ============================================================================
-- STEP 4: VERIFICATION QUERIES
-- ============================================================================

-- Verify tools were created
SELECT 
    id,
    func_name_key,
    label,
    function_type,
    SUBSTRING(sql_query, 1, 50) as query_preview
FROM tool 
WHERE chatbot_id = 2 
ORDER BY id;

-- Verify test data
SELECT 'Total Employees' as metric, COUNT(*) as count FROM employees
UNION ALL
SELECT 'Active Employees', COUNT(*) FROM employees WHERE status = 'ACTIVE'
UNION ALL
SELECT 'Departments', COUNT(*) FROM departments
UNION ALL
SELECT 'Projects', COUNT(*) FROM projects;

-- Sample data preview
SELECT department, COUNT(*) as employee_count, AVG(salary) as avg_salary
FROM employees 
WHERE status = 'ACTIVE'
GROUP BY department
ORDER BY employee_count DESC;

-- ============================================================================
-- SETUP COMPLETE! 
-- ============================================================================
-- You now have:
-- ✓ 3 test tables (employees, departments, projects)
-- ✓ 30 sample employees across 8 departments
-- ✓ 8 department records
-- ✓ 10 project records
-- ✓ 6 SQL tools configured for chatbot ID 2
--
-- Ready for testing!
-- ============================================================================


SELECT 
    *
FROM tool 
WHERE chatbot_id = 2 
ORDER BY id;