-- from function/list/lambdas/expression_iterator_cases.test:7
SET lambda_syntax='DISABLE_SINGLE_ARROW';

-- from function/list/lambdas/expression_iterator_cases.test:12
SELECT list_transform([10], lambda x: sum(1) + x);

-- from function/list/lambdas/expression_iterator_cases.test:17
SELECT list_filter([10], lambda x: sum(1) > 0);

-- from function/list/lambdas/expression_iterator_cases.test:24
SELECT list_transform([NULL, DATE '1992-09-20', DATE '2021-09-20'], lambda elem: extract('year' FROM elem) BETWEEN 2000 AND 2022);
