package org.template.vanilla

import javax.jms._

import akka.actor.{Actor, Props}
import org.apache.predictionio.controller.P2LAlgorithm
import org.apache.predictionio.controller.Params
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import grizzled.slf4j.Logger
import org.apache.activemq.{ActiveMQConnection, ActiveMQConnectionFactory}
import org.apache.predictionio.workflow.CreateServer

class SenderActor extends Actor {
  override def receive: Receive = {
    case "hello" => println("hello world")
  }
}

class RecieverActor extends Actor {
  override def receive: Receive = {
    case "exit" => "no handle"
  }
}

class MySender {
  val connFactory = new ActiveMQConnectionFactory(
    ActiveMQConnection.DEFAULT_USER,
    ActiveMQConnection.DEFAULT_PASSWORD,
    "tcp://localhost:61616")
  var conn = connFactory.createConnection()
  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
  val dest = sess.createQueue("FirstQueue")
  val prod = sess.createProducer(dest)

  def send(s: String): Unit = {
    val textMessage = sess.createTextMessage()
    textMessage.setText(s)

    prod.send(textMessage)
  }
}

class MyReciever extends MessageListener {
  println("主构造开始")
  val connFactory = new ActiveMQConnectionFactory()
  connFactory.setTrustAllPackages(true)
  val conn = connFactory.createConnection()
  val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
  val dest = sess.createQueue("FirstQueue")
  val cons = sess.createConsumer(dest)
  cons.setMessageListener(this)
  conn.start()
  println("主构造结束")

  override def onMessage(message: Message): Unit = {
    println(s"message come on")
    //    Thread.sleep(5000)
    try {
      if (message.isInstanceOf[ObjectMessage]) {
        val msg = message.asInstanceOf[ObjectMessage].getObject.asInstanceOf[MQMessage]
        val msgType = msg.msgType
        msgType match {
          case 0 => {
            val news = msg.news.get
            println("cluster")
            println(s"type:${msgType}, News(${news.category}, ${news.title}, ${news.content})")
          }
          case 1 => {
            val info = msg.info.get
            println("modify")
            println(s"type: ${msgType}, ModifyInfo(${info.id}, ${info.category})")
          }
        }
      }
      println("handle message success")
    } catch {
      case e => {
        e.printStackTrace()
      }
    }
  }
}

case class AlgorithmParams(mult: Int) extends Params

case class News(category: String, title: String, content: String) extends Serializable
case class ModifyInfo(id: Long, category: String) extends Serializable

case class MQMessage(msgType: Int, news: Option[News], info: Option[ModifyInfo]) extends Serializable

// cluster (model, news)
// modify category (id, category)
// get tags
// get recommendation
class Algorithm(val ap: AlgorithmParams)
  // extends PAlgorithm if Model contains RDD[]
  extends P2LAlgorithm[PreparedData, Model, Query, PredictedResult] {
  val myReciever = if (Algorithm.hasLoaded) {
    println("调用")
    new MyReciever
  }

  if (Algorithm.hasLoaded == false) {
    Algorithm.hasLoaded = true
  }

  @transient lazy val logger = Logger[this.type]
  lazy val mySender = {
    println("我也被调用了")
    new MySender
  }
//  val myReciever = new MyReciever
//  lazy val acotorSystem = CreateServer.actorSystem
//  lazy val myActor = {
//    println("启动reciver listener")
//    myReciever.initReciever()
//    acotorSystem.actorOf(Props[SenderActor])
//  }

  def train(sc: SparkContext, data: PreparedData): Model = {
    // Simply count number of events
    // and multiple it by the algorithm parameter
    // and store the number as model
    val count = data.events.count().toInt * ap.mult
    new Model(mc = count)
  }

  def predict(model: Model, query: Query): PredictedResult = {
    // Prefix the query with the model data
    val result = s"${model.mc}-${query.q}"
//    mySender.send("hallo")

    query.q match {
      case "cluster" => {
        val news = MQMessage(0, Some(News("国内", "中国好人", "中国好人")), None)
        val msg = mySender.sess.createObjectMessage(news)
        mySender.prod.send(msg)
        println("发送完成。。。")
      }
      case "modify" => {
        val info = MQMessage(1, None, Some(ModifyInfo(1, "国外")))
        val msg = mySender.sess.createObjectMessage(info)
        mySender.prod.send(msg)
        println("发送完成。。。")
      }
      case _ => {
        println("不支持的查询类型")
      }
    }

    PredictedResult(p = result)
  }
}

class Model(val mc: Int) extends Serializable {
  override def toString = s"mc=${mc}"
}

object Algorithm {
  var hasLoaded = false
}
