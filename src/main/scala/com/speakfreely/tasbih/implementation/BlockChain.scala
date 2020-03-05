package com.speakfreely.tasbih.implementation

import java.util.Date

import cats.effect.Sync
import cats.implicits._
import com.speakfreely.models.{Block, GenesisBlock}
import com.speakfreely.tasbih.algebras.BaseBlockChain
import tsec.common._
import tsec.hashing.jca._

import scala.language.higherKinds
import scala.util.{Failure, Success, Try}

case class BlockChain[F[_] : Sync] private(blocks: Seq[Block]) extends BaseBlockChain[F] {
  def addBlock(data: String): F[BaseBlockChain[F]] = {
    for {
      next <- generateNextBlock(data)
    } yield new BlockChain(next +: blocks)
  }

  def addBlock(block: Block): F[Try[BaseBlockChain[F]]] = for {
    v <- validBlock(block)
  } yield
    if (v)
      Success(new BlockChain(block +: blocks))
    else
      Failure(new IllegalArgumentException("Invalid block added"))

  def firstBlock: Block = blocks.last

  def latestBlock: Block = blocks.head

  private def generateNextBlock(data: String): F[Block] = {
    val previousBlock = latestBlock
    val nextIndex     = previousBlock.index + 1
    val nextTimestamp = new Date().getTime() / 1000 //Fixme: use java.time.LocalDateTime
    for {
      nextHash <- BlockChain.calculateHash(nextIndex, previousBlock.hash, nextTimestamp, data)
    } yield
      Block(nextIndex, previousBlock.hash, nextTimestamp, data, nextHash)
  }

  private def validBlock(data: Block): F[Boolean] = BlockChain.validBlock(data, latestBlock)

}

object BlockChain {
  def apply[F[_]](implicit sync: Sync[F]): BlockChain[F] = new BlockChain(Seq(GenesisBlock))

  def apply[F[_]](blocks: Seq[Block])(implicit sync: Sync[F]): F[Try[BlockChain[F]]] = {
    for {
      valid <- validChain(blocks)
    } yield
      if (valid)
        Success(new BlockChain(blocks))
      else
        Failure(new IllegalArgumentException("Invalid chain specified."))
  }

  //FIXME: this function is not tail-recursive
  private def validChain[F[_]](chain: Seq[Block])(implicit F: Sync[F]): F[Boolean] = {
    chain match {
      case singleBlock :: Nil
        if singleBlock == GenesisBlock => F.delay(true)
      case head :: beforeHead :: tail => for {
        vb <- validBlock(head, beforeHead)
        res <- if (vb) validChain(beforeHead :: tail) else F.delay(false)
      } yield res
      case _ => F.delay(false)
    }
  }

  private def validBlock[F[_]](newBlock: Block, previousBlock: Block)(implicit F: Sync[F]): F[Boolean] = {
    for {
      hash <- calculateHashForBlock(newBlock)
    } yield
      previousBlock.index + 1 == newBlock.index &&
        previousBlock.hash == newBlock.previousHash &&
        hash == newBlock.hash
  }

  private def calculateHashForBlock[F[_]](block: Block)(implicit F: Sync[F]): F[String] = calculateHash(block.index, block.previousHash, block.timestamp, block.data)

  private def hash[F[_]](data: String)(implicit F: Sync[F]): F[String] = for {
    hash <- SHA256.hash[F](data.utf8Bytes)
  } yield hash.toHexString

  private def calculateHash[F[_]](index: Int, previousHash: String, timestamp: Long, data: String)(implicit F: Sync[F]): F[String] =
    hash(s"$index:$previousHash:$timestamp:$data")
}
