-- from types/bit/bit_issue_11211.test:5
PRAGMA enable_verification;

-- from types/bit/bit_issue_11211.test:8
select ( 2::bit & 2::bit ) = 2::bit as b;

-- from types/bit/bit_issue_11211.test:18
FROM
(
  SELECT
  ( 2::bit & 2::bit ) AS a,
  2::bit AS b,
  (a = b) AS '(a = b)',
)
SELECT a, b, a = b, "(a = b)";

-- from types/bit/test_bit.test:8
SELECT ('0101011'::BIT);
