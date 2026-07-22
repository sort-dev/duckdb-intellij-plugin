-- from function/list/lambdas/arrow/expression_iterator_cases_deprecated.test:7
PRAGMA enable_verification;

-- from function/list/lambdas/arrow/expression_iterator_cases_deprecated.test:10
SET lambda_syntax='ENABLE_SINGLE_ARROW';

-- from function/list/lambdas/arrow/expression_iterator_cases_deprecated.test:15
SELECT list_transform([10], x -> sum(1) + x);

-- from function/list/lambdas/arrow/expression_iterator_cases_deprecated.test:20
SELECT list_filter([10], x -> sum(1) > 0);
