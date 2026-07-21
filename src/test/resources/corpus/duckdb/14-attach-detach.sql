ATTACH 'file.db' AS other;

ATTACH DATABASE 'more.db' AS more;

USE other;

DETACH other;
