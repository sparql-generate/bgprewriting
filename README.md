# Query rewriting

This project shows how SPARQL-Generate can be used for the simplest form of query rewriting:  Basic Graph Pattern rewriting.

Given a query 

`SELECT * WHERE { <bgp> } `

The main method in class `TransformBGP` transforms the Basic Graph Pattern `<bgp>` into another Basic Graph Pattern `<bgp2>` , and constructs a new query 

`SELECT * WHERE { <bgp2> } `

The existence of this project is not sufficient to claim that SPARQL-Generate can be used for query rewriting (for now).  