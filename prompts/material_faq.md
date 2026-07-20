# 变量: {dialogue_text}

从以下{domain.name}对话中提取所有{domain.counterparty_label}提出的质疑/异议，以及{domain.role_label}的回应。

## 对话文本
{dialogue_text}

## 输出纯JSON数组（只提取真实出现过的QA对）
[{"question":"{domain.counterparty_label}说了什么质疑","answer":"{domain.role_label}如何回应的"}]
如果没有明确的QA对，输出 []。不要编造。