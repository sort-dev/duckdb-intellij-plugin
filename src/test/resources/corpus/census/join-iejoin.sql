-- from join/iejoin/iejoin_issue_6861.test:5
PRAGMA enable_verification;

-- from join/iejoin/iejoin_issue_6861.test:8
CREATE TABLE test(x INT);

-- from join/iejoin/iejoin_issue_6861.test:11
SET merge_join_threshold=0;

-- from join/iejoin/iejoin_issue_6861.test:14
SELECT * 
FROM test AS a, test AS b 
WHERE (a.x BETWEEN b.x AND b.x);
